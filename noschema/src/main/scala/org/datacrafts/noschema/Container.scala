package org.datacrafts.noschema

abstract class Container[T: NoSchema.ScalaType, C: NoSchema.ScalaType](
  category: NoSchema.Category.Value,
  val element: Context.ContainerElement[T],
  nullable: Boolean = true
) extends NoSchema[C](category = category, nullable = nullable) {
  type Elem = T
}

object Container {

  case class OptionContainer[T](override val element: Context.ContainerElement[T])
    (implicit ot: NoSchema.ScalaType[Option[T]],
      st: NoSchema.ScalaType[T])
    extends Container[T, Option[T]](NoSchema.Category.Option, element, false)

  case class SeqContainer[T](override val element: Context.ContainerElement[T])
    (implicit ot: NoSchema.ScalaType[Seq[T]],
      st: NoSchema.ScalaType[T])
    extends Container[T, Seq[T]](NoSchema.Category.Seq, element)

  case class IterableContainer[T](override val element: Context.ContainerElement[T])
    (implicit ot: NoSchema.ScalaType[Iterable[T]],
      st: NoSchema.ScalaType[T])
    extends Container[T, Iterable[T]](NoSchema.Category.Seq, element)

  case class MapContainer[T](override val element: Context.ContainerElement[T])
    (implicit ot: NoSchema.ScalaType[Map[String, T]],
      st: NoSchema.ScalaType[T])
    extends Container[T, Map[String, T]](NoSchema.Category.Map, element)

  // to support multiple scala Map concrete types
  case class MapContainer2[T](override val element: Context.ContainerElement[T])
    (implicit ot: NoSchema.ScalaType[scala.collection.Map[String, T]],
      st: NoSchema.ScalaType[T])
    extends Container[T, scala.collection.Map[String, T]](NoSchema.Category.Map, element)

  case class SetContainer[T](override val element: Context.ContainerElement[T])
    (implicit ot: NoSchema.ScalaType[Set[T]],
      st: NoSchema.ScalaType[T])
    extends Container[T, Set[T]](NoSchema.Category.Set, element)

}
