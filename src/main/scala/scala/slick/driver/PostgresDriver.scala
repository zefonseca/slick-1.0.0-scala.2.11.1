package scala.slick.driver

import java.util.UUID
import scala.slick.lifted._
import scala.slick.session.{PositionedResult, PositionedParameters}
import scala.slick.ast.{SequenceNode, Library, FieldSymbol, Node}
import scala.slick.util.MacroSupport.macroSupportInterpolation

/**
 * Slick driver for PostgreSQL.
 *
 * This driver implements all capabilities of the
 * [[scala.slick.driver.ExtendedProfile]].
 *
 * Notes:
 *
 * <ul>
 *   <li>[[scala.slick.driver.BasicProfile.capabilities.typeBlob]]:
 *   The default implementation of the <code>Blob</code> type uses the
 *   database type <code>lo</code> and the stored procedure
 *   <code>lo_manage</code>, both of which are provided by the "lo"
 *   extension in PostgreSQL.</li>
 * </ul>
 *
 * @author szeiger
 */
trait PostgresDriver extends ExtendedDriver { driver =>

  override val typeMapperDelegates = new TypeMapperDelegates
  override def createQueryBuilder(input: QueryBuilderInput): QueryBuilder = new QueryBuilder(input)
  override def createTableDDLBuilder(table: Table[_]): TableDDLBuilder = new TableDDLBuilder(table)
  override def createColumnDDLBuilder(column: FieldSymbol, table: Table[_]): ColumnDDLBuilder = new ColumnDDLBuilder(column)

  override def defaultSqlTypeName(tmd: TypeMapperDelegate[_]): String = tmd.sqlType match {
    case java.sql.Types.BLOB => "lo"
    case java.sql.Types.DOUBLE => "DOUBLE PRECISION"
    /* PostgreSQL does not have a TINYINT type, so we use SMALLINT instead. */
    case java.sql.Types.TINYINT => "SMALLINT"
    case _ => super.defaultSqlTypeName(tmd)
  }

  class QueryBuilder(input: QueryBuilderInput) extends super.QueryBuilder(input) {
    override protected val concatOperator = Some("||")

    override protected def buildFetchOffsetClause(fetch: Option[Long], offset: Option[Long]) = (fetch, offset) match {
      case (Some(t), Some(d)) => b" limit $t offset $d"
      case (Some(t), None   ) => b" limit $t"
      case (None,    Some(d)) => b" offset $d"
      case _ =>
    }

    override def expr(n: Node, skipParens: Boolean = false) = n match {
      case Library.NextValue(SequenceNode(name)) => b"nextval('$name')"
      case Library.CurrentValue(SequenceNode(name)) => b"currval('$name')"
      case _ => super.expr(n, skipParens)
    }
  }

  class TableDDLBuilder(table: Table[_]) extends super.TableDDLBuilder(table) {
    override def createPhase1 = super.createPhase1 ++ columns.flatMap {
      case cb: ColumnDDLBuilder => cb.createLobTrigger(table.tableName)
    }
    override def dropPhase1 = {
      val dropLobs = columns.flatMap {
        case cb: ColumnDDLBuilder => cb.dropLobTrigger(table.tableName)
      }
      if(dropLobs.isEmpty) super.dropPhase1
      else Seq("delete from "+quoteIdentifier(table.tableName)) ++ dropLobs ++ super.dropPhase1
    }
  }

  class ColumnDDLBuilder(column: FieldSymbol) extends super.ColumnDDLBuilder(column) {
    override def appendColumn(sb: StringBuilder) {
      sb append quoteIdentifier(column.name) append ' '
      if(autoIncrement) {
        sb append "SERIAL"
        autoIncrement = false
      }
      else sb append sqlType
      appendOptions(sb)
    }

    def lobTrigger(tname: String) =
      quoteIdentifier(tname+"__"+quoteIdentifier(column.name)+"_lob")

    def createLobTrigger(tname: String): Option[String] =
      if(sqlType == "lo") Some(
        "create trigger "+lobTrigger(tname)+" before update or delete on "+
        quoteIdentifier(tname)+" for each row execute procedure lo_manage("+quoteIdentifier(column.name)+")"
      ) else None

    def dropLobTrigger(tname: String): Option[String] =
      if(sqlType == "lo") Some(
        "drop trigger "+lobTrigger(tname)
      ) else None
  }

  class TypeMapperDelegates extends super.TypeMapperDelegates {
    override val byteArrayTypeMapperDelegate = new ByteArrayTypeMapperDelegate
    override val uuidTypeMapperDelegate = new UUIDTypeMapperDelegate

    class ByteArrayTypeMapperDelegate extends super.ByteArrayTypeMapperDelegate {
      override val sqlType = java.sql.Types.BINARY
      override val sqlTypeName = "BYTEA"
      override def setOption(v: Option[Array[Byte]], p: PositionedParameters) = v match {
        case Some(a) => p.setBytes(a)
        case None => p.setNull(sqlType)
      }
    }

    class UUIDTypeMapperDelegate extends super.UUIDTypeMapperDelegate {
      override def sqlTypeName = "UUID"
      override def setValue(v: UUID, p: PositionedParameters) = p.setObject(v, sqlType)
      override def setOption(v: Option[UUID], p: PositionedParameters) = p.setObjectOption(v, sqlType)
      override def nextValue(r: PositionedResult) = r.nextObject().asInstanceOf[UUID]
      override def updateValue(v: UUID, r: PositionedResult) = r.updateObject(v)
      override def valueToSQLLiteral(value: UUID) = "'" + value + "'"
    }
  }
}

object PostgresDriver extends PostgresDriver
