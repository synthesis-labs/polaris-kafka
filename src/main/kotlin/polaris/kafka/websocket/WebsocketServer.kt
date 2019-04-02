package polaris.kafka.websocket

import de.huxhorn.sulky.ulid.ULID
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.streams.kstream.KStream
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer
import polaris.kafka.PolarisKafka
import polaris.kafka.SafeTopic
import java.io.IOException
import java.util.*
import javax.websocket.*
import javax.websocket.server.ServerEndpoint
import javax.websocket.server.ServerEndpointConfig

class WebsocketServer(
    val port: Int,
    val path : String,
    val websocketTopic : SafeTopic<WebsocketEventKey, WebsocketEventValue>) {

    val server : Server
    val connector : ServerConnector
    val context : ServletContextHandler
    val wscontainer : ServerContainer

    init {
        server = Server()
        connector = ServerConnector(server)
        connector.port = port
        server.addConnector(connector)
        context = ServletContextHandler(ServletContextHandler.SESSIONS)
        context.contextPath = "/"
        server.handler = context

        // Boot up the kafka producer
        //
        val processor = PolarisKafka("websocket-processor")

        websocketTopic.startProducer()

        val producer = websocketTopic.producer!!
        val consumer = processor.consumeStream(websocketTopic)

        val endpointHandler = WebsocketServerEndpointHandler(websocketTopic.topic, producer, consumer)

        val serverEndpointConfig =
            ServerEndpointConfig
                .Builder
                .create(
                    WebsocketServerEndpointHandler::class.java,
                    path)
                .configurator(WebsocketServerConfigurator(endpointHandler))
                .build()

        wscontainer = WebSocketServerContainerInitializer.configureContext(context)
        wscontainer.addEndpoint(serverEndpointConfig)

        server.start()
        server.dump(System.out)

        processor.start { topicPartitionAssignment ->
            println("Assigned partitions: $topicPartitionAssignment")
            endpointHandler.partitions = topicPartitionAssignment[websocketTopic.topic]
        }
    }

    fun join() {
        println("Starting websocket server, listening on port $port...")
        server.join()
        println("Websocket server stopped")
    }
}

// This is purely here so we can pass stuff into our WebsocketServerEndpointHandler
//
class WebsocketServerConfigurator(
    val endpointHandler : WebsocketServerEndpointHandler)
    : ServerEndpointConfig.Configurator() {
    override fun <T : Any?> getEndpointInstance(endpointClass: Class<T>?): T {
        return endpointHandler as T
    }
}

@ServerEndpoint(value = "")
class WebsocketServerEndpointHandler(private val topic: String,
                                     private val producer: KafkaProducer<WebsocketEventKey, WebsocketEventValue>,
                                     private val consumer: KStream<WebsocketEventKey, WebsocketEventValue>) {

    private val sessions = Collections.synchronizedSet(HashSet<Session>())
    private val sessionsToWid = mutableMapOf<String, String>()
    private val widToSessions = mutableMapOf<String, String>()
    public var partitions : List<Int>? = null

    init {
        // Anything "SENT" should go to connected sockets
        //
        consumer
            .filter { _, value -> value.getState() == "SENT" }
            .foreach { _, value -> broadcast(value.getData()) }
    }

    @OnOpen
    fun opened(session: Session) {

        val wid = ULID().nextULID().toLowerCase()

        sessions.add(session)
        sessionsToWid[session.id] = wid
        widToSessions[wid] = session.id

        session.maxBinaryMessageBufferSize = 10 * 1024 * 1024
        session.maxTextMessageBufferSize = 10 * 1024 * 1024

        // OPENED event
        //
        val key = WebsocketEventKey(wid)
        val value = WebsocketEventValue(wid, "OPENED", null, getReplyPath(), null)
        val record = ProducerRecord(topic, key, value)
        producer.send(record) { _, exception ->
            if (exception != null) {
                println(exception.toString())
            }
        }
        println("Opened ${session.id} (wid: $wid)")
    }

    fun getReplyPath() : ReplyPath? {
        if (partitions != null) {
            if (!partitions!!.isEmpty()) {
                return ReplyPath(topic, partitions!!.shuffled()[0])
            }
        }
        return null
    }

    fun emitEvent(session : Session, state : String, principle : String?, data : String?) {
        val wid = sessionsToWid[session.id]
        val key = WebsocketEventKey(wid)
        val value = WebsocketEventValue(wid, state, principle, getReplyPath(), data)
        val record = ProducerRecord(topic, key, value)
        producer.send(record) { _, exception ->
            if (exception != null) {
                println(exception.toString())
            }
        }
    }

    @OnMessage
    fun message(session : Session, data : String) {
        println("Message from ${session.id} -> ${data}")

        // RCVD event
        //
        emitEvent(session, "RCVD", null, data)
    }

    @OnClose
    fun closed(session : Session) {
        sessions.remove(session)
        val wid = sessionsToWid[session.id]
        sessionsToWid.remove(session.id)
        widToSessions.remove(wid)

        // CLOSED event
        //
        emitEvent(session, "CLOSED", null, null)

        println("Closed ${session.id}")
    }

    @OnError
    fun error(session : Session, throwable : Throwable) {
        sessions.remove(session)
        val wid = sessionsToWid[session.id]
        sessionsToWid.remove(session.id)
        widToSessions.remove(wid)

        // ERROR event
        //
        emitEvent(session, "ERROR", null, null)

        println("Error ${session.id} -> ${throwable.message}")
    }

    fun send(sessionId : String, data : String) {
        for (session in sessions) {
            if (session.isOpen && session.id.trim().equals(sessionId.trim())) {
                try {
                    session.basicRemote.sendText(data)
                } catch (e: IOException) {
                    e.printStackTrace()
                } catch (e: EncodeException) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun broadcast(data : String) {
        for (session in sessions) {
            if (session.isOpen) {
                try {
                    println("Broadcasting $data")
                    session.basicRemote.sendText(data)
                } catch (e: IOException) {
                    e.printStackTrace()
                } catch (e: EncodeException) {
                    e.printStackTrace()
                }
            }
        }
    }
}