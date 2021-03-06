package scala.slick.memory

import scala.language.{implicitConversions, existentials}
import scala.collection.mutable.Builder
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import scala.slick.action._
import scala.slick.ast._
import TypeUtil._
import scala.slick.compiler._
import scala.slick.profile.{RelationalDriver, RelationalProfile, Capability}
import scala.slick.relational.{ResultConverterCompiler, ResultConverter, CompiledMapping}
import scala.slick.util.{DumpInfo, ??}

/** A profile and driver for interpreted queries on top of the in-memory database. */
trait MemoryProfile extends RelationalProfile with MemoryQueryingProfile { driver: MemoryDriver =>

  type SchemaDescription = SchemaDescriptionDef
  type InsertInvoker[T] = InsertInvokerDef[T]
  type QueryExecutor[R] = QueryExecutorDef[R]
  type Backend = HeapBackend
  val backend: Backend = HeapBackend
  val simple: SimpleQL = new SimpleQL {}
  val Implicit: Implicits = simple
  val api: API = new API {}

  lazy val queryCompiler = compiler + new MemoryCodeGen
  lazy val updateCompiler = compiler
  lazy val deleteCompiler = compiler
  lazy val insertCompiler = QueryCompiler(Phase.assignUniqueSymbols, new InsertCompiler(InsertCompiler.NonAutoInc), new MemoryInsertCodeGen)

  override protected def computeCapabilities = super.computeCapabilities ++ MemoryProfile.capabilities.all

  def createQueryExecutor[R](tree: Node, param: Any): QueryExecutor[R] = new QueryExecutorDef[R](tree, param)
  def createInsertInvoker[T](tree: Node): InsertInvoker[T] = new InsertInvokerDef[T](tree)
  def createDDLInvoker(sd: SchemaDescription): DDLInvoker = sd.asInstanceOf[DDLInvoker]
  def buildSequenceSchemaDescription(seq: Sequence[_]): SchemaDescription = ??
  def buildTableSchemaDescription(table: Table[_]): SchemaDescription = new TableDDL(table)

  type QueryActionExtensionMethods[R, S <: NoStream] = QueryActionExtensionMethodsImpl[R, S]
  type StreamingQueryActionExtensionMethods[R, T] = StreamingQueryActionExtensionMethodsImpl[R, T]
  type SchemaActionExtensionMethods = SchemaActionExtensionMethodsImpl
  type InsertActionExtensionMethods[T] = InsertActionExtensionMethodsImpl[T]

  def createQueryActionExtensionMethods[R, S <: NoStream](tree: Node, param: Any): QueryActionExtensionMethods[R, S] =
    new QueryActionExtensionMethods[R, S](tree, param)
  def createStreamingQueryActionExtensionMethods[R, T](tree: Node, param: Any): StreamingQueryActionExtensionMethods[R, T] =
    new StreamingQueryActionExtensionMethods[R, T](tree, param)
  def createSchemaActionExtensionMethods(schema: SchemaDescription): SchemaActionExtensionMethods =
    new SchemaActionExtensionMethodsImpl(schema)
  def createInsertActionExtensionMethods[T](compiled: CompiledInsert): InsertActionExtensionMethods[T] =
    new InsertActionExtensionMethodsImpl[T](compiled)

  lazy val MappedColumnType = new MappedColumnTypeFactory

  class MappedColumnTypeFactory extends super.MappedColumnTypeFactory {
    def base[T : ClassTag, U : BaseColumnType](tmap: T => U, tcomap: U => T): BaseColumnType[T] = {
      assertNonNullType(implicitly[BaseColumnType[U]])
      new MappedColumnType(implicitly[BaseColumnType[U]], tmap, tcomap)
    }
  }

  class MappedColumnType[T, U](val baseType: ColumnType[U], toBase: T => U, toMapped: U => T)(implicit val classTag: ClassTag[T]) extends ScalaType[T] with BaseTypedType[T] {
    def nullable: Boolean = baseType.nullable
    def ordered: Boolean = baseType.ordered
    def scalaOrderingFor(ord: Ordering): scala.math.Ordering[T] = new scala.math.Ordering[T] {
      val uOrdering = baseType.scalaOrderingFor(ord)
      def compare(x: T, y: T): Int = uOrdering.compare(toBase(x), toBase(y))
    }
  }

  trait Implicits extends super[RelationalProfile].Implicits with super[MemoryQueryingProfile].Implicits {
    implicit def ddlToDDLInvoker(sd: SchemaDescription): DDLInvoker = createDDLInvoker(sd)
  }

  trait SimpleQL extends super[RelationalProfile].SimpleQL with super[MemoryQueryingProfile].SimpleQL with Implicits

  trait API extends super[RelationalProfile].API with super[MemoryQueryingProfile].API

  protected def createInterpreter(db: Backend#Database, param: Any): QueryInterpreter = new QueryInterpreter(db, param) {
    override def run(n: Node) = n match {
      case ResultSetMapping(_, from, CompiledMapping(converter, _)) :@ CollectionType(cons, el) =>
        val fromV = run(from).asInstanceOf[TraversableOnce[Any]]
        val b = cons.createBuilder(el.classTag).asInstanceOf[Builder[Any, Any]]
        b ++= fromV.map(v => converter.asInstanceOf[ResultConverter[MemoryResultConverterDomain, _]].read(v.asInstanceOf[QueryInterpreter.ProductValue]))
        b.result()
      case n => super.run(n)
    }
  }

  class QueryExecutorDef[R](tree: Node, param: Any) extends super.QueryExecutorDef[R] {
    def run(implicit session: Backend#Session): R = createInterpreter(session.database, param).run(tree).asInstanceOf[R]
  }

  class InsertInvokerDef[T](tree: Node) extends super.InsertInvokerDef[T] {
    protected[this] val ResultSetMapping(_, Insert(_, table: TableNode, _), CompiledMapping(converter, _)) = tree

    type SingleInsertResult = Unit
    type MultiInsertResult = Unit

    def += (value: T)(implicit session: Backend#Session) {
      val htable = session.database.getTable(table.tableName)
      val buf = htable.createInsertRow
      converter.asInstanceOf[ResultConverter[MemoryResultConverterDomain, Any]].set(value, buf)
      htable.append(buf)
    }

    def ++= (values: Iterable[T])(implicit session: Backend#Session): Unit =
      values.foreach(this += _)
  }

  abstract class DDL extends SchemaDescriptionDef with DDLInvoker { self =>
    def ++(other: SchemaDescription): SchemaDescription = {
      val d = Implicit.ddlToDDLInvoker(other)
      new DDL {
        def create(implicit session: Backend#Session) { self.create; d.create }
        def drop(implicit session: Backend#Session) { self.drop; d.drop }
      }
    }
  }

  class TableDDL(table: Table[_]) extends DDL {
    def create(implicit session: Backend#Session): Unit =
      session.database.createTable(table.tableName,
        table.create_*.map { fs => new HeapBackend.Column(fs, typeInfoFor(fs.tpe)) }.toIndexedSeq,
        table.indexes.toIndexedSeq, table.tableConstraints.toIndexedSeq)
    def drop(implicit session: Backend#Session): Unit =
      session.database.dropTable(table.tableName)
  }

  type DriverAction[-E <: Effect, +R, +S <: NoStream] = DriverActionDef[E, R, S]
  type StreamingDriverAction[-E <: Effect, +R, +T] = StreamingDriverActionDef[E, R, T]

  protected[this] def dbAction[E <: Effect, R, S <: NoStream](f: Backend#Session => R): DriverAction[E, R, S] = new DriverAction[E, R, S] with SynchronousDatabaseAction[Backend#This, E, R, S] {
    def run(ctx: ActionContext[Backend]): R = f(ctx.session)
    def getDumpInfo = DumpInfo("MemoryProfile.DriverAction")
  }

  class StreamingQueryAction[R, T](tree: Node, param: Any) extends StreamingDriverAction[Effect.Read, R, T] with SynchronousDatabaseAction[Backend#This, Effect.Read, R, Streaming[T]] {
    type StreamState = Iterator[T]
    protected[this] def getIterator(ctx: ActionContext[Backend]): Iterator[T] = {
      val inter = createInterpreter(ctx.session.database, param)
      val ResultSetMapping(_, from, CompiledMapping(converter, _)) = tree
      val pvit = inter.run(from).asInstanceOf[TraversableOnce[QueryInterpreter.ProductValue]].toIterator
      pvit.map(converter.asInstanceOf[ResultConverter[MemoryResultConverterDomain, T]].read _)
    }
    def run(ctx: ActionContext[Backend]): R =
      createInterpreter(ctx.session.database, param).run(tree).asInstanceOf[R]
    override def emitStream(ctx: StreamingActionContext[Backend], limit: Long, state: StreamState): StreamState = {
      val it = if(state ne null) state else getIterator(ctx)
      var count = 0L
      while(count < limit && it.hasNext) {
        count += 1
        ctx.emit(it.next)
      }
      if(it.hasNext) it else null
    }
    def head: DriverAction[Effect.Read, T, NoStream] = new DriverAction[Effect.Read, T, NoStream] with SynchronousDatabaseAction[Backend#This, Effect.Read, T, NoStream] {
      def run(ctx: ActionContext[Backend]): T = getIterator(ctx).next
      def getDumpInfo = DumpInfo("MemoryProfile.StreamingQueryAction.first")
    }
    def headOption: DriverAction[Effect.Read, Option[T], NoStream] = new DriverAction[Effect.Read, Option[T], NoStream] with SynchronousDatabaseAction[Backend#This, Effect.Read, Option[T], NoStream] {
      def run(ctx: ActionContext[Backend]): Option[T] = {
        val it = getIterator(ctx)
        if(it.hasNext) Some(it.next) else None
      }
      def getDumpInfo = DumpInfo("MemoryProfile.StreamingQueryAction.firstOption")
    }
    def getDumpInfo = DumpInfo("MemoryProfile.StreamingQueryAction")
  }

  class QueryActionExtensionMethodsImpl[R, S <: NoStream](tree: Node, param: Any) extends super.QueryActionExtensionMethodsImpl[R, S] {
    def result: DriverAction[Effect.Read, R, S] =
      new StreamingQueryAction[R, Nothing](tree, param).asInstanceOf[DriverAction[Effect.Read, R, S]]
  }

  class StreamingQueryActionExtensionMethodsImpl[R, T](tree: Node, param: Any) extends QueryActionExtensionMethodsImpl[R, Streaming[T]](tree, param) with super.StreamingQueryActionExtensionMethodsImpl[R, T] {
    override def result: StreamingDriverAction[Effect.Read, R, T] = super.result.asInstanceOf[StreamingDriverAction[Effect.Read, R, T]]
  }

  class SchemaActionExtensionMethodsImpl(schema: SchemaDescription) extends super.SchemaActionExtensionMethodsImpl {
    def create = dbAction(createDDLInvoker(schema).create(_))
    def drop = dbAction(createDDLInvoker(schema).drop(_))
  }

  class InsertActionExtensionMethodsImpl[T](compiled: CompiledInsert) extends super.InsertActionExtensionMethodsImpl[T] {
    protected[this] val inv = createInsertInvoker[T](compiled)
    type SingleInsertResult = Unit
    type MultiInsertResult = Unit
    def += (value: T) = dbAction(inv.+=(value)(_))
    def ++= (values: Iterable[T]) = dbAction(inv.++=(values)(_))
  }
}

object MemoryProfile {
  object capabilities {
    /** Supports all MemoryProfile features which do not have separate capability values */
    val other = Capability("memory.other")

    /** All MemoryProfile capabilities */
    val all = Set(other)
  }
}

trait MemoryDriver extends RelationalDriver with MemoryQueryingDriver with MemoryProfile { driver =>

  override val profile: MemoryProfile = this

  class InsertMappingCompiler(insert: Insert) extends ResultConverterCompiler[MemoryResultConverterDomain] {
    val Insert(_, table: TableNode, ProductNode(cols)) = insert
    val tableColumnIdxs = table.driverTable.asInstanceOf[Table[_]].create_*.zipWithIndex.toMap

    def createColumnConverter(n: Node, idx: Int, column: Option[FieldSymbol]): ResultConverter[MemoryResultConverterDomain, _] =
      new InsertResultConverter(tableColumnIdxs(column.get))

    class InsertResultConverter(tidx: Int) extends ResultConverter[MemoryResultConverterDomain, Any] {
      def read(pr: MemoryResultConverterDomain#Reader) = ??
      def update(value: Any, pr: MemoryResultConverterDomain#Updater) = ??
      def set(value: Any, pp: MemoryResultConverterDomain#Writer) = pp(tidx) = value
      override def getDumpInfo = super.getDumpInfo.copy(mainInfo = s"tidx=$tidx")
      def width = 1
    }
  }

  class MemoryInsertCodeGen extends CodeGen {
    def compileServerSideAndMapping(serverSide: Node, mapping: Option[Node], state: CompilerState) =
      (serverSide, mapping.map(new InsertMappingCompiler(serverSide.asInstanceOf[Insert]).compileMapping))
  }
}

object MemoryDriver extends MemoryDriver
