package akka.stream.scaladsl

import akka.stream.{ SourceShape, Graph }

import scala.collection.{ immutable, GenTraversableOnce }

sealed trait Hub[Id, Out] {
  def sourceIds: Set[Id]
  def +(source: (Id, Graph[SourceShape[Out], _])): Hub[Id, Out]
  def ++(sources: GenTraversableOnce[(Id, Graph[SourceShape[Out], _])]): Hub[Id, Out]
  def -(sourceId: Id): Hub[Id, Out]
  def --(sourceIds: GenTraversableOnce[Id]): Hub[Id, Out]
}

object Hub {

  /**
   * INTERNAL API
   */
  private[stream] class HubImpl[Id, Out](val currentSourceIds: Set[Id], val newSources: immutable.Map[Id, Graph[SourceShape[Out], _]]) extends Hub[Id, Out] {
    override def sourceIds: Set[Id] = currentSourceIds ++ newSources.keySet
    override def +(source: (Id, Graph[SourceShape[Out], _])): Hub[Id, Out] = new HubImpl(currentSourceIds + source._1, newSources + source)
    override def ++(sources: GenTraversableOnce[(Id, Graph[SourceShape[Out], _])]): Hub[Id, Out] = new HubImpl(currentSourceIds ++ sources.toTraversable.map(_._1), newSources ++ sources)
    override def -(sourceId: Id): Hub[Id, Out] = new HubImpl(currentSourceIds - sourceId, newSources - sourceId)
    override def --(sourceIds: GenTraversableOnce[Id]): Hub[Id, Out] = new HubImpl(currentSourceIds -- sourceIds, newSources -- sourceIds)
  }

  def empty[Id, Out]: Hub[Id, Out] = new HubImpl(Set.empty, immutable.Map.empty)
  def apply[Id, Out](sources: immutable.Map[Id, Source[Out, _]]): Hub[Id, Out] = new HubImpl(sources.keySet, sources)
}
