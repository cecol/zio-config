package zio.config

import java.{ util => ju }
import zio.UIO

import scala.collection.JavaConverters._
import scala.collection.immutable.Nil
import zio.config.PropertyTree.unflatten
import zio.ZIO
import java.io.FileInputStream
import java.io.File
import zio.Task

final case class ConfigSource[K, V](
  getConfigValue: Vector[K] => PropertyTree[K, V],
  sourceDescription: List[String]
) { self =>
  final def orElse(that: => ConfigSource[K, V]): ConfigSource[K, V] =
    ConfigSource(
      k => getConfigValue(k).getOrElse(that.getConfigValue(k)),
      if (self.sourceDescription == that.sourceDescription) self.sourceDescription
      else self.sourceDescription ++ that.sourceDescription
    )

  final def <>(that: => ConfigSource[K, V]): ConfigSource[K, V] =
    self orElse that
}

object ConfigSource {
  val SystemEnvironment = "system environment"
  val SystemProperties  = "system properties"

  def empty[K, V]: ConfigSource[K, V] =
    ConfigSource(_ => PropertyTree.empty, Nil)

  def fromMap(
    map: Map[String, String],
    source: String = "constant",
    keyDelimiter: Char = '.',
    valueDelimter: Option[Char] = None
  ): ConfigSource[String, String] =
    fromMapInternal(map)(
      x => {
        val listOfValues = valueDelimter.fold(List(x))(delim => x.split(delim).toList)
        ::(listOfValues.head, listOfValues.tail)
      },
      keyDelimiter,
      source
    )

  def fromMultiMap(
    map: Map[String, ::[String]],
    source: String = "constant",
    keyDelimiter: Char = '.'
  ): ConfigSource[String, String] =
    fromMapInternal(map)(identity, keyDelimiter, source)

  def fromProperties(
    property: ju.Properties,
    source: String = "properties",
    keyDelimiter: Char = '.',
    valueDelimiter: Option[Char] = None
  ): ConfigSource[String, String] = {
    val mapString = property.stringPropertyNames().asScala.foldLeft(Map.empty[String, String]) { (acc, a) =>
      acc.updated(a, property.getProperty(a))
    }

    mergeAll(
      PropertyTree
        .fromStringMap(mapString, keyDelimiter, valueDelimiter)
        .map(tree => fromPropertyTree(tree, source))
    )
  }

  def fromPropertiesFile[A](
    filePath: String,
    keyDelimiter: Char = '.',
    valueDelimiter: Option[Char] = None
  ): Task[ConfigSource[String, String]] =
    for {
      properties <- ZIO.bracket(ZIO.effect(new FileInputStream(new File(filePath))))(r => ZIO.effectTotal(r.close()))(
                     inputStream => {
                       ZIO.effect {
                         val properties = new java.util.Properties()
                         properties.load(inputStream)
                         properties
                       }
                     }
                   )
    } yield ConfigSource.fromProperties(properties, filePath, keyDelimiter, valueDelimiter)

  def fromSystemEnv: UIO[ConfigSource[String, String]] =
    fromSystemEnv(None)

  def fromSystemEnv(valueDelimiter: Option[Char]): UIO[ConfigSource[String, String]] =
    UIO
      .effectTotal(sys.env)
      .map(map => ConfigSource.fromMap(map, SystemEnvironment, '_', valueDelimiter))

  def fromSystemProperties: UIO[ConfigSource[String, String]] =
    fromSystemProperties(None)

  def fromSystemProperties(valueDelimiter: Option[Char]): UIO[ConfigSource[String, String]] =
    for {
      systemProperties <- UIO.effectTotal(java.lang.System.getProperties)
    } yield ConfigSource.fromProperties(
      property = systemProperties,
      source = SystemProperties,
      valueDelimiter = valueDelimiter
    )

  private[config] def fromMapInternal[A, B](
    map: Map[String, A]
  )(f: A => ::[B], keyDelimiter: Char, source: String): ConfigSource[String, B] =
    fromPropertyTrees(
      unflatten(
        map.map(
          tuple =>
            tuple._1.split(keyDelimiter).toVector.filterNot(_.trim == "") ->
              f(tuple._2)
        )
      ),
      source
    )

  private[config] def fromPropertyTree[B](
    tree: PropertyTree[String, B],
    source: String
  ): ConfigSource[String, B] =
    ConfigSource(
      (path: Vector[String]) => tree.getPath(path.toList),
      source :: Nil
    )

  private[config] def fromPropertyTrees[B](
    trees: Iterable[PropertyTree[String, B]],
    sourceName: String
  ): ConfigSource[String, B] =
    mergeAll(trees.map(fromPropertyTree(_, sourceName)))

  private[config] def mergeAll[K, V](sources: Iterable[ConfigSource[K, V]]): ConfigSource[K, V] =
    sources.foldLeft(ConfigSource.empty: ConfigSource[K, V])(_ orElse _)

}
