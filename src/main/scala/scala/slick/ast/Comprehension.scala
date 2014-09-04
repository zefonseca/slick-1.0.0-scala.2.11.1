package scala.slick.ast

import Util._

/** A SQL comprehension */
case class Comprehension(from: Seq[(Symbol, Node)] = Seq.empty, where: Seq[Node] = Seq.empty, groupBy: Option[Node] = None, orderBy: Seq[(Node, Ordering)] = Seq.empty, select: Option[Node] = None, fetch: Option[Long] = None, offset: Option[Long] = None) extends Node with DefNode {
  val nodeChildren = from.map(_._2) ++ where ++ groupBy ++ orderBy.map(_._1) ++ select
  override def nodeChildNames =
    from.map("from " + _._1) ++
    where.zipWithIndex.map("where" + _._2) ++
    groupBy.map(_ => "groupBy") ++
    orderBy.map("orderBy " + _._2) ++
    select.map(_ => "select")
  def nodeMapChildren(f: Node => Node) = mapChildren(f, f)
  def mapChildren(fromMap: Node => Node, otherMap: Node => Node): Node = {
    val fromO = nodeMapNodes(from.view.map(_._2), fromMap)
    val whereO = nodeMapNodes(where, otherMap)
    val groupByO = groupBy.map(otherMap)
    val orderByO = nodeMapNodes(orderBy.map(_._1), otherMap)
    val selectO = select.map(otherMap)
    if(fromO.isDefined || whereO.isDefined || groupByO != groupBy || orderByO.isDefined || selectO != select)
      copy(
        from = fromO.map(f => from.view.map(_._1).zip(f)).getOrElse(from),
        where = whereO.getOrElse(where),
        groupBy = groupByO,
        orderBy = orderByO.map(_.zip(orderBy.map(_._2))).getOrElse(orderBy),
        select = selectO
      )
    else this
  }
  def nodeGenerators = from
  override def toString = "Comprehension(fetch = "+fetch+", offset = "+offset+")"
  def nodeMapGenerators(f: Symbol => Symbol) = {
    val gens = from.map(_._1)
    mapOrNone(gens, f) match {
      case Some(s) => copy(from = from.zip(s).map { case ((_, n), s) => (s, n) })
      case None => this
    }
  }
  def nodePostGeneratorChildren = select.toSeq
  def nodeMapScopedChildren(f: (Option[Symbol], Node) => Node) = {
    val fn = (n: Node) => f(None, n)
    val from2 = from.map{ case (s, n) => f(Some(s), n) }
    val fromO = if(from.zip(from2).forall{ case ((_, n1), n2) => n1 eq n2 }) None else Some(from2)
    val whereO = nodeMapNodes(where, fn)
    val groupByO = groupBy.map(fn)
    val orderByO = nodeMapNodes(orderBy.map(_._1), fn)
    val selectO = select.map(fn)
    if(fromO.isDefined || whereO.isDefined || groupByO != groupBy || orderByO.isDefined || selectO != select)
      copy(
        from = fromO.map(f => from.view.map(_._1).zip(f)).getOrElse(from),
        where = whereO.getOrElse(where),
        groupBy = groupByO,
        orderBy = orderByO.map(_.zip(orderBy.map(_._2))).getOrElse(orderBy),
        select = selectO
      )
    else this
  }
}

/** The row_number window function */
final case class RowNumber(by: Seq[(Node, Ordering)] = Seq.empty) extends SimpleNode {
  lazy val nodeChildren = by.map(_._1)
  protected[this] def nodeRebuild(ch: IndexedSeq[Node]) =
    copy(by = by.zip(ch).map{ case ((_, o), n) => (n, o) })
  override def nodeChildNames = by.zipWithIndex.map("by" + _._2)
  override def toString = "RowNumber"
}
