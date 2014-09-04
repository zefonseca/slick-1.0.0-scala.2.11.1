package scala.slick.util

/**
 * An iterator on top of a data source which does not offer a hasNext()
 * method without doing a next()
 */
trait ReadAheadIterator[+T] extends BufferedIterator[T] {

  private[this] var state = 0 // 0: no data, 1: cached, 2: finished
  private[this] var cached: T = null.asInstanceOf[T]

  protected[this] final def finished(): T = {
    state = 2
    null.asInstanceOf[T]
  }

  /** Return a new value or call finished() */
  protected def fetchNext(): T

  def head: T = {
    update()
    if(state == 1) cached
    else throw new NoSuchElementException("head on empty iterator")
  }

  @deprecated("Use headOption instead of peek()", "0.10.0-M2")
  def peek(): Option[T] = headOption

  def headOption: Option[T] = {
    update()
    if(state == 1) Some(cached)
    else None
  }

  private[this] def update() {
    if(state == 0) {
      cached = fetchNext()
      if(state == 0) state = 1
    }
  }

  def hasNext: Boolean = {
    update()
    state == 1
  }

  def next(): T = {
    update()
    if(state == 1) {
      state = 0
      cached
    } else throw new NoSuchElementException("next on empty iterator");
  }
}
