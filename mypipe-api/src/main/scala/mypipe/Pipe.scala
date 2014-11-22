package mypipe

import scala.concurrent.duration._
import org.slf4j.LoggerFactory
import mypipe.mysql._
import mypipe.api.{ Mutation, Producer }
import akka.actor.{ Cancellable, ActorSystem }

class Pipe(id: String, consumers: List[BinaryLogConsumer], producer: Producer) {

  protected val log = LoggerFactory.getLogger(getClass)
  protected var CONSUMER_DISCONNECT_WAIT_SECS = 2
  protected val system = ActorSystem("mypipe")
  implicit val ec = system.dispatcher

  @volatile protected var _connected: Boolean = false
  protected var threads = List.empty[Thread]
  protected var flusher: Option[Cancellable] = None

  protected val listener = new BinaryLogConsumerListener() {

    override def onConnect(consumer: BinaryLogConsumerTrait) {
      log.info(s"Pipe $id connected!")

      _connected = true

      flusher = Some(system.scheduler.schedule(Conf.FLUSH_INTERVAL_SECS seconds,
        Conf.FLUSH_INTERVAL_SECS seconds) {
          Conf.binlogSaveFilePosition(consumer.hostname, consumer.port,
            BinaryLogFilePosition(consumer.asInstanceOf[BaseBinaryLogConsumer].client.getBinlogFilename, consumer.asInstanceOf[BaseBinaryLogConsumer].client.getBinlogPosition),
            id)
          // TODO: if flush fails, stop and disconnect
          producer.flush
        })
    }

    override def onDisconnect(consumer: BinaryLogConsumerTrait) {
      log.info(s"Pipe $id disconnected!")
      _connected = false
      flusher.foreach(_.cancel())
      Conf.binlogSaveFilePosition(
        consumer.hostname,
        consumer.port,
        BinaryLogFilePosition(consumer.asInstanceOf[BaseBinaryLogConsumer].client.getBinlogFilename, consumer.asInstanceOf[BaseBinaryLogConsumer].client.getBinlogPosition),
        id)
      producer.flush
    }

    override def onMutation(consumer: BinaryLogConsumerTrait, mutation: Mutation[_]): Boolean = {
      producer.queue(mutation)
    }

    override def onMutation(consumer: BinaryLogConsumerTrait, mutations: Seq[Mutation[_]]): Boolean = {
      producer.queueList(mutations.toList)
    }
  }

  def isConnected = _connected

  def connect() {

    if (threads.size > 0) {

      log.warn("Attempting to reconnect pipe while already connected, aborting!")

    } else {

      threads = consumers.map(c ⇒ {
        c.registerListener(listener)
        val t = new Thread() {
          override def run() {
            log.info(s"Connecting pipe between $c -> $producer")
            c.connect()
          }
        }

        t.start()
        t
      })
    }
  }

  def disconnect() {
    for (
      c ← consumers;
      t ← threads
    ) {
      try {
        log.info(s"Disconnecting pipe between ${c} -> ${producer}")
        c.disconnect()
        t.join(CONSUMER_DISCONNECT_WAIT_SECS * 1000)
      } catch {
        case e: Exception ⇒ log.error(s"Caught exception while trying to disconnect from ${c.hostname}:${c.port} at binlog position ${c.binlogFileAndPos}.")
      }
    }
  }

  override def toString(): String = id
}

