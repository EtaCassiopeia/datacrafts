package org.datacrafts.noschema.operator

import org.datacrafts.noschema.{NoSchema, Operation, ShapelessCoproduct, ShapelessProduct}
import org.datacrafts.noschema.ShapelessCoproduct.TypeValueExtractor
import org.datacrafts.noschema.operator.ShapelessCoproductOperator.CoproductBuilder

class ShapelessCoproductTupler[T](
  override val operation: Operation[T],
  override val shapeless: ShapelessCoproduct[T, _]
) extends ShapelessCoproductOperator [T, (String, Any)]{

  override protected def parse(input: Any): TypeValueExtractor = input match {
    case (key, value) =>
      new TypeValueExtractor {
        override def getTypeValue(scalaType: NoSchema.ScalaType[_]): Option[Any] = {
          if (s"$key" == scalaType.fullName) Some(value) else None
        }

        override def toString: String = s"UnionTypeExtractor: input=${input}"
      }
    case _ => throw new Exception(s"input ${input.getClass} does not match key value pair")
  }

  override protected def newCoproductBuilder(): CoproductBuilder[(String, Any)] =
    new CoproductBuilder[(String, Any)] {
      private var tuple: Option[(String, Any)] = None

      override def build(): (String, Any) = tuple.getOrElse(
        throw new Exception("union type value is empty"))

      override def addTypeValue(scalaType: NoSchema.ScalaType[_],
        value: Any
      ): ShapelessCoproduct.UnionTypeValueCollector = {
        if (tuple.isDefined) {
          throw new Exception(s"adding value for corpoduct should only be invoked once")
        }
        tuple = Some((scalaType.fullName, value))
        this
      }
    }
}
