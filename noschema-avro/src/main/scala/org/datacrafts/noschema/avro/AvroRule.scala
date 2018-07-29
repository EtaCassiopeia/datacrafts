package org.datacrafts.noschema.avro

import scala.collection.JavaConverters._

import org.apache.avro.Schema
import org.apache.avro.Schema.Field
import org.datacrafts.logging.Slf4jLogging
import org.datacrafts.noschema.{Container, NoSchema, NoSchemaDsl, Operation, Primitive, ShapelessCoproduct, ShapelessProduct}
import org.datacrafts.noschema.Container.{IterableContainer, MapContainer, MapContainer2, SeqContainer}
import org.datacrafts.noschema.Context.{CoproductElement, LocalContext}
import org.datacrafts.noschema.Operation.Operator
import org.datacrafts.noschema.avro.AvroRule.SchemaWrapper
import org.datacrafts.noschema.operator.ContainerOperator.{IterableOperator, MapOperator, MapOperator2, SeqOperator}
import org.datacrafts.noschema.operator.PrimitiveOperator
import org.datacrafts.noschema.rule.DefaultRule

object AvroRule {
  val NameSpacePattern = """(.+)\.(.+)""".r

  object Default extends AvroRule with Slf4jLogging.Default

  case class SchemaWrapper(name: String, nameSpace: String, wrapperField: String)
}

trait AvroRule extends DefaultRule with NoSchemaDsl {
  Self: Slf4jLogging =>

  def getSchemaDoc(operation: AvroOperation[_]): String = {
    null // scalastyle:ignore
  }

  def getFieldDoc(operation: AvroOperation[_]): String = {
    null // scalastyle:ignore
  }

  // from shapeless' perspective, there's really no well defined way to tell enum from union
  // scrooge makes them extend different traits, ThriftUnion, ThriftEnum
  // generally, enum's subclasses are all modules(case objects),
  // except that scrooge add one unknown field
  // union's subclasses are all classes
  def isUnion(shapeless: ShapelessCoproduct[_, _]): Boolean = {
    shapeless.scalaType.typeTag.tpe.typeSymbol.asClass
      .knownDirectSubclasses.forall(!_.isModuleClass)
  }

  def isEnum(shapeless: ShapelessCoproduct[_, _]): Boolean = {
    shapeless.scalaType.typeTag.tpe.typeSymbol.asClass
      .knownDirectSubclasses.forall(_.isModuleClass)
  }

  def getEnumValue(context: LocalContext[_]): String = {
    context.noSchema.scalaType.shortName
  }

  def getSchemaWrapper(operation: AvroOperation[_]): Option[SchemaWrapper] = {

    operation.parentOp match {
      // avro does not support nested union
      // if the member of a union is also a union, must wrap it
      case Some(parentOp) if parentOp.isUnion && operation.isUnion =>
        operation.context.localContext match {
          case coproductElem: CoproductElement[_] =>
            Some(
              SchemaWrapper(
                name = coproductElem.symbol.name,
                nameSpace = parentOp.context.noSchema.scalaType.fullName,
                wrapperField = getWrapFieldName(operation)
              )
            )
          case other =>
            throw new Exception(
              s"does not have rule to wrap context ${operation.context}, " +
                s"need to extend the wrapper rule")
        }

      case _ => None
    }
  }

  def getWrapFieldName(wrappedOperation: AvroOperation[_]): String = {
    wrappedOperation.context.noSchema.scalaType.shortName
  }

  import scala.reflect.runtime.universe.typeOf

  def getSchema(operation: AvroOperation[_]): Schema = {
    operation.context.noSchema match {

      // primitive
      case primitive: Primitive[_] =>
        primitive.scalaType.typeTag.tpe match {
          case t if t <:< typeOf[Short] => Schema.create(Schema.Type.INT)
          case t if t <:< typeOf[Int] => Schema.create(Schema.Type.INT)
          case t if t <:< typeOf[Long] => Schema.create(Schema.Type.LONG)
          case t if t <:< typeOf[Double] => Schema.create(Schema.Type.DOUBLE)
          case t if t <:< typeOf[Float] => Schema.create(Schema.Type.FLOAT)
          case t if t <:< typeOf[Boolean] => Schema.create(Schema.Type.BOOLEAN)
          case t if t <:< typeOf[String] => Schema.create(Schema.Type.STRING)
          case t if t <:< typeOf[Array[Byte]] => Schema.create(Schema.Type.BYTES)
          case _ => throw new Exception(s"no primitive avro schema for ${primitive}")
        }

      // option, add null to Union if needed
      case option: Container[_, _] if option.category == NoSchema.Category.Option =>
        val elementOp = operation.dependencyOperation(option.element)
        if (elementOp.isNullable) {
          elementOp.avroSchema
        } else if ( // union but not nullable
          elementOp.avroSchema.getType == Schema.Type.UNION
        ) {
          // add NULL type to the union
          Schema.createUnion(
            (elementOp.avroSchema.getTypes.asScala :+ Schema.create(Schema.Type.NULL)).asJava
          )
        } else { // not union can be wrapped
          Schema.createUnion(
            elementOp.avroSchema,
            Schema.create(Schema.Type.NULL)
          )
        }

      // map
      case map: Container[_, _] if map.category == NoSchema.Category.Map =>
        Schema.createMap(operation.dependencyOperation(map.element).avroSchema)

      // array
      case seq: Container[_, _] if seq.category == NoSchema.Category.Seq =>
        Schema.createArray(operation.dependencyOperation(seq.element).avroSchema)

      // record
      case shapeless: ShapelessProduct[_, _] =>
        shapeless.scalaType.fullName match {
          case AvroRule.NameSpacePattern(namespace, name) =>
            Schema.createRecord(
              name,
              getSchemaDoc(operation),
              namespace,
              false,
              shapeless.dependencies.map {
                case dep =>
                  val depOp = operation.dependencyOperation(dep)
                  new Field(
                    dep.symbol.name,
                    depOp.avroSchema,
                    getFieldDoc(depOp),
                    // no default value needed for avro schema,
                    // since there won't be missing values
                    // when generating avro record from scala class.
                    // default in avro schema plays the same role as the default in operator,
                    // which is needed when marshaling from avro input to scala class
                    null.asInstanceOf[Any] // scalastyle:ignore
                  )
              }.toSeq.sortBy(_.name()).asJava
            )
          case _ =>
            throw new Exception(
              s"${shapeless.scalaType.fullName} cannot extract namespace.name")
        }

      // union
      case shapeless: ShapelessCoproduct[_, _] if operation.isUnion =>
        Schema.createUnion(
          shapeless.dependencies.map {
            dep =>
              operation.dependencyOperation(dep).avroSchema
          }.sortBy(_.getName).asJava
        )

      // enum
      case shapeless: ShapelessCoproduct[_, _] if operation.isEnum =>
        shapeless.scalaType.fullName match {
          case AvroRule.NameSpacePattern(namespace, name) =>
            Schema.createEnum(
              name,
              getSchemaDoc(operation),
              namespace,
              shapeless.dependencies.map {
                dep => getEnumValue(dep)
              }.asJava
            )

          case _ =>
            throw new Exception(
              s"${shapeless.scalaType.fullName} cannot extract namespace.name")
        }

      case shapeless: ShapelessCoproduct[_, _] =>
        throw new Exception(
          s"Coproduct is neither union nor enum, need to update avroRule to cover the case" +
            s"\n${shapeless.format()}")

      case other => throw new Exception(s"Missing avro schema rule for:\n${other.format()}")
    }
  }

  // for unmarshal, need to produce avro accepted data structures
  def getAvroOperator[V](operation: AvroOperation[V]): Operator[V] = {

    import scala.collection.JavaConverters._
    import scala.reflect.runtime.universe.typeOf
    operation.context.noSchema match {

      case bytes: Primitive[V] if bytes.scalaType.typeTag.tpe <:< typeOf[Array[Byte]] =>
        new PrimitiveOperator[Array[Byte]](
          operation.asInstanceOf[Operation[Array[Byte]]]
        ) {
          override def marshalNoneNull(input: Any): Array[Byte] = {
            input match {
              case byteBuffer: java.nio.ByteBuffer => byteBuffer.array()
              case _ => super.marshalNoneNull(input)
            }
          }
          override def unmarshalNoneNull(input: Array[Byte]): Any = {
            java.nio.ByteBuffer.wrap(input)
          }
        }.asInstanceOf[Operator[V]]

      case shapeless: ShapelessProduct[V, _] =>
        new ShapelessProductAvroOperator[V](shapeless, operation, this)

      case shapeless: ShapelessCoproduct[V, _] =>
        new ShapelessCoproductAvroOperator[V](shapeless, operation, this)

      case map: MapContainer[_] =>
        new MapOperator(
          map.element,
          operation.asInstanceOf[Operation[Map[String, map.Elem]]]
        ) {

          protected override def unmarshalNoneNull(input: Map[String, Elem]): Any =
            input.map {
              case (k, v) => k -> elementOperation.operator.unmarshal(v)
            }.asJava
        }.asInstanceOf[Operator[V]]

      case map: MapContainer2[_] =>
        new MapOperator2(
          map.element,
          operation.asInstanceOf[Operation[scala.collection.Map[String, map.Elem]]]
        ) {
          protected override def unmarshalNoneNull(
            input: scala.collection.Map[String, Elem]): Any =
            input.map {
              case (k, v) => k -> elementOperation.operator.unmarshal(v)
            }.asJava
        }.asInstanceOf[Operator[V]]

      case seq: SeqContainer[_] =>
        new SeqOperator(
          seq.element,
          operation.asInstanceOf[Operation[Seq[seq.Elem]]]
        ) {
          protected override def unmarshalNoneNull(input: Seq[Elem]): Any =
            input.map(elementOperation.operator.unmarshal).asJava
        }.asInstanceOf[Operator[V]]

      case iterable: IterableContainer[_] =>
        new IterableOperator(
          iterable.element,
          operation.asInstanceOf[Operation[Iterable[iterable.Elem]]]
        ) {
          protected override def unmarshalNoneNull(input: Iterable[Elem]): Any =
            input.map(elementOperation.operator.unmarshal).asJava
        }.asInstanceOf[Operator[V]]

      case _ => super.getOperator(operation)
    }
  }
}