package org.datacrafts.noschema.implicits

import scala.util.{Failure, Success, Try}

import org.datacrafts.logging.Slf4jLogging
import org.datacrafts.noschema.{schemaClassFilter, Context, NoSchema, NoSchemaCoproduct, Operation}
import org.datacrafts.noschema.NoSchema.HasLazySchema
import org.datacrafts.noschema.implicits.ShapelessCoproduct.ShapelessCoproductAdapter
import org.datacrafts.noschema.operator.CoproductOperator.{TypeValueExtractor, UnionTypeValueCollector}
import shapeless.{:+:, CNil, Coproduct, Inl, Inr, LabelledGeneric, Lazy, Witness}
import shapeless.labelled.{field, FieldType}

private class ShapelessCoproduct[T, R <: Coproduct](
  members: Seq[Context.CoproductElement[_]],
  generic: LabelledGeneric.Aux[T, R],
  shapeless: ShapelessCoproductAdapter[R],
  st: NoSchema.ScalaType[T]
) extends NoSchemaCoproduct[T](members)(st)
{
  override def marshal(typeExtractor: TypeValueExtractor, operation: Operation[T]): T = {
    generic.from(shapeless.marshalCoproduct(typeExtractor, operation))
  }

  override def unmarshal(input: T, emptyUnion: UnionTypeValueCollector, operation: Operation[T]
  ): UnionTypeValueCollector = {
    shapeless.unmarshalCoproduct(generic.to(input), emptyUnion, operation)
  }
}

object ShapelessCoproduct extends Slf4jLogging.Default {

  trait Instances {
    implicit def shapelessCoproductRecursiveBuilder
    [K <: Symbol, V, L <: Coproduct](implicit
      headSymbol: Lazy[Witness.Aux[K]],
      head: Lazy[NoSchema[V]],
      tail: Lazy[ShapelessCoproductAdapter[L]]
    ): ShapelessCoproductAdapter[FieldType[K, V] :+: L] = {

      // coproduct type won't trigger infinite recursion like product,
      // since inheritence cannot go backwards
      val headValueContext =
        Context.CoproductElement(
          headSymbol.value.value,
          head.value
        )

      new ShapelessCoproductAdapter[FieldType[K, V] :+: L](
        members = tail.value.members :+ headValueContext) {

        override def marshalCoproduct(
          typeValueExtractor: TypeValueExtractor, operation: Operation[_]
        ): FieldType[K, V] :+: L = {
          // if the extractor can determine the current product element does not match,
          // it should return None.
          // This is the case for Enum where the element.symbol matches with the input EnumSymbol.
          // For record type, however, the input is the record structure,
          // there is no way to determine its type directly,
          // but has to always return the Some(input) and let each of the type to try marshaling it
          typeValueExtractor.getTypeValue(headValueContext) match {
            case Some(value) =>
              Try(
                Inl[FieldType[K, V], L](
                field[K](operation.dependencyOperation(headValueContext).marshal(value)))
              ) match {
                case Success(result) => result
                case Failure(f) =>
                  logDebug(
                    s"coproduct marshaling intermediate attempt failures are expected: " +
                      s"failed to marshal value $value for " +
                      s"${headValueContext}\nreason=${f.getMessage}")
                  Inr[FieldType[K, V], L](
                    tail.value.marshalCoproduct(typeValueExtractor, operation))
              }
            case None =>
              Inr[FieldType[K, V], L](tail.value.marshalCoproduct(typeValueExtractor, operation))
          }
        }

        override def unmarshalCoproduct(
          coproduct: FieldType[K, V] :+: L,
          emptyUnion: UnionTypeValueCollector, operation: Operation[_]
        ): UnionTypeValueCollector = {
          coproduct match {
            case Inl(headValue) => emptyUnion.addTypeValue(
              headValueContext,
              operation.dependencyOperation(headValueContext).unmarshal(headValue)
            )
            case Inr(tailValue) => tail.value.unmarshalCoproduct(
              tailValue, emptyUnion, operation
            )
          }
        }
      }
    }

    implicit def shapelessCoproductRecursiveBuilderTerminator[K <: Symbol, V](
      implicit
      headSymbol: Lazy[Witness.Aux[K]],
      headValue: Lazy[NoSchema[V]]
    ): ShapelessCoproductAdapter[FieldType[K, V] :+: CNil] = {

      val headValueContext =
        Context.CoproductElement(
          headSymbol.value.value,
          headValue.value
        )

      new ShapelessCoproductAdapter[FieldType[K, V] :+: CNil](
        members = Seq(headValueContext)) {

        override def marshalCoproduct(
          typeExtractor: TypeValueExtractor, operation: Operation[_]): FieldType[K, V] :+: CNil = {

          if (schemaClassFilter.contains(headValueContext.noSchema.scalaType.fullName)) {
            throw new Exception(s"${headValueContext} is last in coproduct and blacklisted," +
              s" no value found for any type from $typeExtractor\n" +
              s"${operation.format()}")
          }
          typeExtractor.getTypeValue(headValueContext) match {
            case Some(value) =>
              Inl[FieldType[K, V], CNil](
                field[K](operation.dependencyOperation(headValueContext).marshal(value)))
            case None =>
              throw new Exception(s"no value found for any type from $typeExtractor\n" +
                s"${operation.format()}")
          }
        }

        override def unmarshalCoproduct(
          coproduct: FieldType[K, V] :+: CNil,
          emptyUnion: UnionTypeValueCollector, operation: Operation[_]
        ): UnionTypeValueCollector = {
          coproduct match {
            case Inl(value) => emptyUnion.addTypeValue(
              headValueContext,
              operation.dependencyOperation(headValueContext).unmarshal(value)
            )
            case _ => throw new Exception("impossible")
          }
        }
      }
    }

    implicit def shapelessCoproductBridging[T, R <: Coproduct](implicit
      generic: Lazy[LabelledGeneric.Aux[T, R]],
      shapeless: Lazy[ShapelessCoproductAdapter[R]],
      st: Lazy[NoSchema.ScalaType[T]]
    ): NoSchema[T] = NoSchema.getOrElseCreateSchema(
      shapeless.value.composeWithGeneric(generic.value, st.value))(st.value)
  }

  abstract class ShapelessCoproductAdapter[R <: Coproduct](
    val members: Seq[Context.CoproductElement[_]]
  ) {
    def marshalCoproduct(typeExtractor: TypeValueExtractor, operation: Operation[_]): R

    def unmarshalCoproduct(
      coproduct: R, emptyUnion: UnionTypeValueCollector, operation: Operation[_]
    ): UnionTypeValueCollector

    def composeWithGeneric[T](
      generic: LabelledGeneric.Aux[T, R],
      st: NoSchema.ScalaType[T]): NoSchemaCoproduct[T] =
      new ShapelessCoproduct[T, R](
        // filter the UnknownUnionField
        // this field will not produce value in unmarshaling,
        // and is not intended to take value in marshaling,
        // since schema evolution should never leave out already known types to unknown
        members.filter(clazz => !schemaClassFilter.contains(clazz.noSchema.scalaType.fullName)),
        generic, this, st)
  }
}
