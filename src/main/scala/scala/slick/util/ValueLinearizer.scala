package scala.slick.util

import scala.language.higherKinds
import scala.collection.generic.CanBuildFrom
import scala.slick.driver.BasicProfile
import scala.slick.session.{PositionedParameters, PositionedResult}
import scala.slick.ast.Node

/**
 * Converts between unpacked (e.g. in query results) and linearized (a
 * sequence of columns) form of values.
 */
sealed trait ValueLinearizer[T] {
  def narrowedLinearizer: RecordLinearizer[_]
}

/**
 * A linearizer for collection values.
 */
trait CollectionLinearizer[F[+_], T] extends ValueLinearizer[F[T]] {
  def elementLinearizer: ValueLinearizer[T]
  def canBuildFrom: CanBuildFrom[Nothing, T, F[T]]
  final def narrowedLinearizer = elementLinearizer.narrowedLinearizer
}

/**
 * A linearizer for record values.
 */
trait RecordLinearizer[T] extends ValueLinearizer[T] {
  def getResult(profile: BasicProfile, rs: PositionedResult): T
  def updateResult(profile: BasicProfile, rs: PositionedResult, value: T): Unit
  def setParameter(profile: BasicProfile, ps: PositionedParameters, value: Option[T]): Unit
  def getLinearizedNodes: IndexedSeq[Node]
  final def narrowedLinearizer = this
}

trait DelegateRecordLinearizer[T] extends RecordLinearizer[T] {
  protected[this] def valueLinearizer: RecordLinearizer[T]
  final def getResult(profile: BasicProfile, rs: PositionedResult): T = valueLinearizer.getResult(profile, rs)
  final def updateResult(profile: BasicProfile, rs: PositionedResult, value: T): Unit = valueLinearizer.updateResult(profile, rs, value)
  final def setParameter(profile: BasicProfile, ps: PositionedParameters, value: Option[T]): Unit = valueLinearizer.setParameter(profile, ps, value)
  final def getLinearizedNodes: IndexedSeq[Node] = valueLinearizer.getLinearizedNodes
}

class ProductLinearizer[T <: Product](sub: IndexedSeq[RecordLinearizer[_]]) extends RecordLinearizer[T] {

  def getLinearizedNodes: IndexedSeq[Node] =
    (0 until sub.length).flatMap(i => sub(i).asInstanceOf[RecordLinearizer[Any]].getLinearizedNodes)(collection.breakOut)

  def setParameter(profile: BasicProfile, ps: PositionedParameters, value: Option[T]) =
    for(i <- 0 until sub.length)
      sub(i).asInstanceOf[RecordLinearizer[Any]].setParameter(profile, ps, value.map(_.productElement(i)))

  def updateResult(profile: BasicProfile, rs: PositionedResult, value: T) =
    for(i <- 0 until sub.length)
      sub(i).asInstanceOf[RecordLinearizer[Any]].updateResult(profile, rs, value.productElement(i))

  def getResult(profile: BasicProfile, rs: PositionedResult): T = {
    var i = -1
    def f = { i += 1; sub(i).getResult(profile, rs) }
    val tuple = sub.length match {
      case 2 => (f,f)
      case 3 => (f,f,f)
      case 4 => (f,f,f,f)
      case 5 => (f,f,f,f,f)
      case 6 => (f,f,f,f,f,f)
      case 7 => (f,f,f,f,f,f,f)
      case 8 => (f,f,f,f,f,f,f,f)
      case 9 => (f,f,f,f,f,f,f,f,f)
      case 10 => (f,f,f,f,f,f,f,f,f,f)
      case 11 => (f,f,f,f,f,f,f,f,f,f,f)
      case 12 => (f,f,f,f,f,f,f,f,f,f,f,f)
      case 13 => (f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 14 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 15 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 16 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 17 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 18 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 19 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 20 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 21 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f)
      case 22 => (f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f,f)
    }
    tuple.asInstanceOf[T]
  }
}
