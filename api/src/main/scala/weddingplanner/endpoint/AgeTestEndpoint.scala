package weddingplanner.endpoint

import java.time.{Instant, LocalDate}

import cats.effect.IO
import io.finch.Endpoint
import lspace._
import Label.D._
import lspace.codec.{jsonld, ActiveContext, ActiveProperty, NativeTypeDecoder, NativeTypeEncoder}
import lspace.decode.{DecodeJson, DecodeJsonLD}
import lspace.encode.{EncodeJson, EncodeJsonLD, EncodeText}
import lspace.ns.vocab.schema
import lspace.provider.detached.DetachedGraph
import lspace.services.LApplication
import monix.eval.Task
import shapeless.{:+:, CNil, HNil}
import weddingplanner.ns.AgeTest

import scala.collection.immutable.ListMap
import scala.util.{Failure, Success, Try}

object AgeTestEndpoint {
  def apply(graph: Graph, activeContext: ActiveContext = ActiveContext())(
      implicit baseDecoder: lspace.codec.NativeTypeDecoder,
      baseEncoder: lspace.codec.NativeTypeEncoder): AgeTestEndpoint =
    new AgeTestEndpoint(graph)(baseDecoder, baseEncoder, activeContext)

  lazy val activeContext = ActiveContext(
    `@prefix` = ListMap(
      "person"     -> AgeTest.keys.person.iri,
      "minimumAge" -> AgeTest.keys.requiredMinAge.iri,
      "targetDate" -> AgeTest.keys.targetDate.iri
    ),
    definitions = Map(
      AgeTest.keys.person.iri -> ActiveProperty(`@type` = schema.Person :: Nil, property = AgeTest.keys.person)(),
      AgeTest.keys.requiredMinAge.iri -> ActiveProperty(`@type` = `@int` :: Nil,
                                                        property = AgeTest.keys.requiredMinAge)(),
      AgeTest.keys.targetDate.iri -> ActiveProperty(`@type` = `@date` :: Nil, property = AgeTest.keys.targetDate)()
    )
  )
}

class AgeTestEndpoint(graph: Graph)(implicit val baseDecoder: lspace.codec.NativeTypeDecoder,
                                    val baseEncoder: lspace.codec.NativeTypeEncoder,
                                    activeContext: ActiveContext)
    extends Endpoint.Module[IO] {

  implicit val ec = monix.execution.Scheduler.global

  type Json = baseDecoder.Json
  implicit val bd: NativeTypeDecoder.Aux[Json] = baseDecoder.asInstanceOf[NativeTypeDecoder.Aux[Json]]

  import lspace.services.codecs.Decode._
  implicit val decoder = lspace.codec.jsonld.Decoder(DetachedGraph)

  import io.finch._
  import lspace.Implicits.AsyncGuide.guide

  implicit val dateTimeDecoder: DecodeEntity[Instant] =
    DecodeEntity.instance(s =>
      Try(Instant.parse(s)) match {
        case Success(instant) => Right(instant)
        case Failure(error)   => Left(error)
    })

  implicit class WithGraphTask(graphTask: Task[Graph]) {
    def ++(graph: Graph) =
      for {
        graph0 <- graphTask
        graph1 <- graph0 ++ graph
      } yield graph1
  }

  /**
    * tests if a kinsman path exists between two
    * TODO: update graph with latest (remote) data
    */
  val age: Endpoint[IO, Boolean :+: Boolean :+: CNil] = {
    implicit val bodyJsonldTyped = DecodeJsonLD
      .bodyJsonldTyped(AgeTest.ontology, AgeTest.fromNode)

    implicit val jsonToNodeToT = DecodeJson
      .jsonToNodeToT(AgeTest.ontology, AgeTest.fromNode)

    implicit val decodeDate: DecodeEntity[LocalDate] = new DecodeEntity[LocalDate] {
      def apply(s: String): Either[Throwable, LocalDate] =
        try {
          Right(LocalDate.parse(s))
        } catch {
          case e: Throwable => Left(e)
        }
    }

    import shapeless.::
    get(param[String]("id") :: param[Int]("minimumAge") :: paramOption[LocalDate]("targetDate"))
      .mapOutputAsync {
        case person :: minimumAge :: targetDate :: HNil =>
          (for {
            result <- g
              .N()
              .hasIri(s"${graph.iri}/person/" + person)
              .has(schema.birthDate, P.lt(targetDate.getOrElse(LocalDate.now()).minusYears(minimumAge)))
              .head()
              .withGraph(graph)
              .headOptionF
              .map(_.isDefined)
              .map(Ok(_))
          } yield result).to[IO]
//        case _ => Task.now(NotAcceptable(new Exception("invalid parameters"))).to[IO]
      } :+: post(body[Task[AgeTest], lspace.services.codecs.Application.JsonLD :+: Application.Json :+: CNil])
      .mapOutputAsync {
        case task =>
          task
            .flatMap {
              case ageTest: AgeTest if ageTest.result.isDefined =>
                Task.now(NotAcceptable(new Exception("result should not yet be defined")))
              case ageTest: AgeTest =>
                for {
                  ageSatisfaction <- g.N
                    .hasIri(ageTest.person)
                    .has(schema.birthDate,
                         P.lt(ageTest.targetDate.getOrElse(LocalDate.now()).minusYears(ageTest.requiredMinAge)))
                    .head()
                    .withGraph(graph)
                    .headOptionF
                    .map(_.isDefined)
                } yield {
                  Ok(ageSatisfaction)
                }
              case _ => Task.now(NotAcceptable(new Exception("invalid parameters")))
            }
            .to[IO]
      }
  }

  val api = "age" :: age

  lazy val compiled: Endpoint.Compiled[IO] = {
    type Json = baseEncoder.Json
    implicit val be: NativeTypeEncoder.Aux[Json] = baseEncoder.asInstanceOf[NativeTypeEncoder.Aux[Json]]

    import lspace.services.codecs.Encode._
    implicit val encoder = jsonld.Encoder.apply(be)

    import EncodeJson._
    import EncodeJsonLD._
    import EncodeText._

    Bootstrap
      .configure(enableMethodNotAllowed = true, enableUnsupportedMediaType = true)
      .serve[Text.Plain :+: Application.Json :+: LApplication.JsonLD :+: CNil](api)
      .compile
  }
}
