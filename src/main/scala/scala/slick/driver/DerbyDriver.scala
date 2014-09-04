package scala.slick.driver

import scala.slick.SlickException
import scala.slick.lifted._
import scala.slick.ast._
import scala.slick.util.MacroSupport.macroSupportInterpolation

/**
 * Slick driver for Derby/JavaDB.
 *
 * This driver implements the [[scala.slick.driver.ExtendedProfile]]
 * ''without'' the following capabilities:
 *
 * <ul>
 *   <li>[[scala.slick.driver.BasicProfile.capabilities.functionDatabase]]:
 *     <code>Functions.database</code> is not available in Derby. Slick
 *     will return an empty string instead.</li>
 *   <li>[[scala.slick.driver.BasicProfile.capabilities.pagingNested]]:
 *     See <a href="https://issues.apache.org/jira/browse/DERBY-5911"
 *     target="_parent">DERBY-5911</a>.</li>
 *   <li>[[scala.slick.driver.BasicProfile.capabilities.returnInsertOther]]:
 *     When returning columns from an INSERT operation, only a single column
 *     may be specified which must be the table's AutoInc column.</li>
 *   <li>[[scala.slick.driver.BasicProfile.capabilities.sequenceCurr]]:
 *     <code>Sequence.curr</code> to get the current value of a sequence is
 *     not supported by Derby. Trying to generate SQL code which uses this
 *     feature throws a SlickException.</li>
 *   <li>[[scala.slick.driver.BasicProfile.capabilities.sequenceCycle]]:
 *     Sequence cycling is supported but does not conform to SQL:2008
 *     semantics. Derby cycles back to the START value instead of MINVALUE or
 *     MAXVALUE.</li>
 *   <li>[[scala.slick.driver.BasicProfile.capabilities.zip]]:
 *     Ordered sub-queries and window functions with orderings are currently
 *     not supported by Derby. These are required by <code>zip</code> and
 *     <code>zipWithIndex</code>. Trying to generate SQL code which uses this
 *     feature causes the DB to throw an exception. We do not prevent these
 *     queries from being generated because we expect future Derby versions to
 *     support them with the standard SQL:2003 syntax (see
 *     <a href="http://wiki.apache.org/db-derby/OLAPRowNumber" target="_parent"
 *     >http://wiki.apache.org/db-derby/OLAPRowNumber</a>).</li>
 * </ul>
 *
 * @author szeiger
 */
trait DerbyDriver extends ExtendedDriver { driver =>

  override val capabilities: Set[Capability] = (BasicProfile.capabilities.all
    - BasicProfile.capabilities.functionDatabase
    - BasicProfile.capabilities.pagingNested
    - BasicProfile.capabilities.returnInsertOther
    - BasicProfile.capabilities.sequenceCurr
    // Cycling is broken in Derby. It cycles to the start value instead of min or max
    - BasicProfile.capabilities.sequenceCycle
    - BasicProfile.capabilities.zip
  )

  override val typeMapperDelegates = new TypeMapperDelegates
  override def createQueryBuilder(input: QueryBuilderInput): QueryBuilder = new QueryBuilder(input)
  override def createTableDDLBuilder(table: Table[_]): TableDDLBuilder = new TableDDLBuilder(table)
  override def createColumnDDLBuilder(column: FieldSymbol, table: Table[_]): ColumnDDLBuilder = new ColumnDDLBuilder(column)
  override def createSequenceDDLBuilder(seq: Sequence[_]): SequenceDDLBuilder[_] = new SequenceDDLBuilder(seq)

  override def defaultSqlTypeName(tmd: TypeMapperDelegate[_]): String = tmd.sqlType match {
    case java.sql.Types.BOOLEAN => "SMALLINT"
    /* Derby does not have a TINYINT type, so we use SMALLINT instead. */
    case java.sql.Types.TINYINT => "SMALLINT"
    case _ => super.defaultSqlTypeName(tmd)
  }

  class QueryBuilder(input: QueryBuilderInput) extends super.QueryBuilder(input) {

    override protected val scalarFrom = Some("sysibm.sysdummy1")
    override protected val supportsTuples = false
    override protected val useIntForBoolean = true

    override def expr(c: Node, skipParens: Boolean = false): Unit = c match {
      case Library.IfNull(l, r) => r match {
        /* Derby does not support IFNULL so we use COALESCE instead,
         * and it requires NULLs to be casted to a suitable type */
        case c: Column[_] =>
          b"coalesce(cast($l as ${c.typeMapper(driver).sqlTypeName}),!$r)"
        case _ => throw new SlickException("Cannot determine type of right-hand side for ifNull")
      }
      case c @ BindColumn(v) if currentPart == SelectPart =>
        /* The Derby embedded driver has a bug (DERBY-4671) which results in a
         * NullPointerException when using bind variables in a SELECT clause.
         * This should be fixed in Derby 10.6.1.1. The workaround is to add an
         * explicit type annotation (in the form of a CAST expression). */
        val tmd = c.typeMapper(profile)
        b"cast("
        b +?= { (p, param) => tmd.setValue(v, p) }
        b" as ${tmd.sqlTypeName})"
      case Library.NextValue(SequenceNode(name)) => b"(next value for `$name)"
      case Library.CurrentValue(_*) => throw new SlickException("Derby does not support CURRVAL")
      case _ => super.expr(c, skipParens)
    }
  }

  class TableDDLBuilder(table: Table[_]) extends super.TableDDLBuilder(table) {
    override protected def createIndex(idx: Index) = {
      if(idx.unique) {
        /* Create a UNIQUE CONSTRAINT (with an automatically generated backing
         * index) because Derby does not allow a FOREIGN KEY CONSTRAINT to
         * reference columns which have a UNIQUE INDEX but not a nominal UNIQUE
         * CONSTRAINT. */
        val sb = new StringBuilder append "ALTER TABLE " append quoteIdentifier(table.tableName) append " ADD "
        sb append "CONSTRAINT " append quoteIdentifier(idx.name) append " UNIQUE("
        addIndexColumnList(idx.on, sb, idx.table.tableName)
        sb append ")"
        sb.toString
      } else super.createIndex(idx)
    }
  }

  class ColumnDDLBuilder(column: FieldSymbol) extends super.ColumnDDLBuilder(column) {
    override protected def appendOptions(sb: StringBuilder) {
      if(defaultLiteral ne null) sb append " DEFAULT " append defaultLiteral
      if(notNull) sb append " NOT NULL"
      if(primaryKey) sb append " PRIMARY KEY"
      if(autoIncrement) sb append " GENERATED BY DEFAULT AS IDENTITY"
    }
  }

  class SequenceDDLBuilder[T](seq: Sequence[T]) extends super.SequenceDDLBuilder(seq) {
    override def buildDDL: DDL = {
      import seq.integral._
      val increment = seq._increment.getOrElse(one)
      val desc = increment < zero
      val b = new StringBuilder append "CREATE SEQUENCE " append quoteIdentifier(seq.name)
      /* Set the START value explicitly because it defaults to the data type's
       * min/max value instead of the more conventional 1/-1. */
      b append " START WITH " append seq._start.getOrElse(if(desc) -1 else 1)
      seq._increment.foreach { b append " INCREMENT BY " append _ }
      seq._maxValue.foreach { b append " MAXVALUE " append _ }
      seq._minValue.foreach { b append " MINVALUE " append _ }
      /* Cycling is supported but does not conform to SQL:2008 semantics. Derby
       * cycles back to the START value instead of MINVALUE or MAXVALUE. No good
       * workaround available AFAICT. */
      if(seq._cycle) b append " CYCLE"
      DDL(b.toString, "DROP SEQUENCE " + quoteIdentifier(seq.name))
    }
  }

  class TypeMapperDelegates extends super.TypeMapperDelegates {
    override val booleanTypeMapperDelegate = new BooleanTypeMapperDelegate
    override val uuidTypeMapperDelegate = new UUIDTypeMapperDelegate

    /* Derby does not have a proper BOOLEAN type. The suggested workaround is
     * SMALLINT with constants 1 and 0 for TRUE and FALSE. */
    class BooleanTypeMapperDelegate extends super.BooleanTypeMapperDelegate {
      override def valueToSQLLiteral(value: Boolean) = if(value) "1" else "0"
    }

    class UUIDTypeMapperDelegate extends super.UUIDTypeMapperDelegate {
      override def sqlType = java.sql.Types.BINARY
      override def sqlTypeName = "CHAR(16) FOR BIT DATA"
    }
  }
}

object DerbyDriver extends DerbyDriver
