package scala.slick.session

import java.util.Properties
import java.sql._
import javax.sql.DataSource
import javax.naming.InitialContext
import scala.util.DynamicVariable
import scala.slick.SlickException

/**
 * A database instance to which connections can be created.
 * Encapsulates either a DataSource or parameters for DriverManager.getConnection().
 */
abstract class Database {

  protected[session] def createConnection(): Connection

  /**
   * The DatabaseCapabilities, accessed through a Session and created by the
   * first Session that needs them. Access does not need to be synchronized
   * because, in the worst case, capabilities will be determined multiple
   * times by different concurrent sessions but the result should always be
   * the same.
   */
  @volatile
  protected[session] var capabilities: DatabaseCapabilities = null

  /**
   * Create a new session. The session needs to be closed explicitly by calling its close() method.
   */
  def createSession(): Session = new BaseSession(this)

  /**
   * Run the supplied function with a new session and automatically close the session at the end.
   */
  def withSession[T](f: Session => T): T = {
    val s = createSession()
    try { f(s) } finally s.close()
  }

  /**
   * Run the supplied thunk with a new session and automatically close the session at the end.
   * The session is stored in a thread-local variable which can be accessed with the implicit
   * function in Database.Implicit.
   */
  def withSession[T](f: => T): T = withSession { s: Session => Database.dyn.withValue(s)(f) }

  /**
   * Run the supplied function with a new session in a transaction and automatically close the session at the end.
   */
  def withTransaction[T](f: Session => T): T = withSession { s => s.withTransaction(f(s)) }

  /**
   * Run the supplied thunk with a new session in a transaction and automatically close the session at the end.
   * The session is stored in a thread-local variable which can be accessed with the implicit
   * function in Database.Implicit.
   */
  def withTransaction[T](f: => T): T = withSession { Database.threadLocalSession.withTransaction(f) }
}

/**
 * Factory methods for creating Database objects.
 */
object Database {

  private[session] val dyn = new DynamicVariable[Session](null)

  /**
   * An implicit function that returns the thread-local session in a withSession block
   */
  implicit def threadLocalSession: Session = {
    val s = dyn.value
    if(s eq null)
      throw new SQLException("No implicit session available; threadLocalSession can only be used within a withSession block")
    else s
  }

  /**
   * Create a Database based on a DataSource.
   */
  def forDataSource(ds: DataSource): Database = new Database {
    protected[session] def createConnection(): Connection = ds.getConnection
  }

  /**
   * Create a Database based on the JNDI name of a DataSource.
   */
  def forName(name: String) = new InitialContext().lookup(name) match {
    case ds: DataSource => forDataSource(ds)
    case x => throw new SlickException("Expected a DataSource for JNDI name "+name+", but got "+x)
  }

  /**
   * Create a Database that uses the DriverManager to open new connections.
   */
  def forURL(url:String, user:String = null, password:String = null, prop: Properties = null, driver:String = null): Database = new Database {
    if(driver ne null) Class.forName(driver)
    val cprop = if(prop.ne(null) && user.eq(null) && password.eq(null)) prop else {
      val p = new Properties(prop)
      if(user ne null) p.setProperty("user", user)
      if(password ne null) p.setProperty("password", password)
      p
    }

    protected[session] def createConnection(): Connection = DriverManager.getConnection(url, cprop)
  }

  /**
   * Create a Database that directly uses a Driver to open new connections.
   * This is needed to open a JDBC URL with a driver that was not loaded by
   * the system ClassLoader.
   */
  def forDriver(driver:Driver, url:String, user:String = null, password:String = null, prop: Properties = null): Database = new Database {
    val cprop = if(prop.ne(null) && user.eq(null) && password.eq(null)) prop else {
      val p = new Properties(prop)
      if(user ne null) p.setProperty("user", user)
      if(password ne null) p.setProperty("password", password)
      p
    }

    protected[session] def createConnection(): Connection = {
      val conn = driver.connect(url, cprop)
      if(conn eq null)
        throw new SQLException("Driver "+driver+" does not know how to handle URL "+url, "08001")
      conn
    }
  }

  /**
   * Create a Database that uses the DriverManager to open new connections.
   */
  def forURL(url:String, prop: Map[String, String]): Database = {
    val p = new Properties
    if(prop ne null)
      for((k,v) <- prop) if(k.ne(null) && v.ne(null)) p.setProperty(k, v)
    forURL(url, prop = p)
  }
}
