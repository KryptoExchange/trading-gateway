package ru.krypto.gateway.kafka

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import ru.krypto.gateway.config.KafkaConfig
import ru.krypto.gateway.services.OrderBookCacheService
import ru.krypto.gateway.services.TickerCacheService
import ru.krypto.gateway.services.TradeCacheService
import java.time.Duration
import java.util.*

data class MarketDataMessage(
    val tradingPair: String = "",
    val askSize: Int = 0,
    val bidSize: Int = 0,
    val askPrices: LongArray? = null,
    val askVolumes: LongArray? = null,
    val askOrders: LongArray? = null,
    val bidPrices: LongArray? = null,
    val bidVolumes: LongArray? = null,
    val bidOrders: LongArray? = null,
    val timestamp: Long = 0,
    val referenceSeq: Long = 0,
    val timestampISO: String = ""
)

data class TradeOrderEventMessage(
    val orderId: String = "",
    val makerOrderId: String? = null,
    val orderStatus: String = "",
    val maker: Boolean? = null,
    val executedQuantity: String? = null,
    val executionPrice: String? = null,
    val isCompleted: Boolean = false,
    val tradeUUID: String? = null,
    val userId: String? = null,
    val tradingPair: String? = null,
    val marketType: String? = null,
    val side: String? = null,
    val fee: String? = null,
    val counterpartyFee: String? = null,
    val feeAsset: String? = null,
    val leverage: Int? = null,
    val marginMode: String? = null,
    val executedAt: String? = null,
    val isLiquidation: Boolean = false
)

data class SessionStatisticsMessage(
    val symbol: String = "",
    val openPrice: Long = 0,
    val highPrice: Long = 0,
    val lowPrice: Long = 0,
    val closePrice: Long = 0,
    val volume: Long = 0,
    val timestamp: Long = 0,
    val sessionStart: Long = 0,
    val sessionEnd: Long = 0
)

class MarketDataConsumer(
    private val kafkaConfig: KafkaConfig,
    private val orderBookCacheService: OrderBookCacheService,
    private val tradeCacheService: TradeCacheService,
    private val tickerCacheService: TickerCacheService
) {
    private val logger = LoggerFactory.getLogger(MarketDataConsumer::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) {
            val props = Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, kafkaConfig.consumerGroupId)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
            }

            val consumer = KafkaConsumer<String, String>(props)
            consumer.subscribe(
                listOf(
                    kafkaConfig.topics.marketState,
                    kafkaConfig.topics.tradeOrderEvents,
                    kafkaConfig.topics.marketOhclv
                )
            )

            logger.info("Market data consumer started, subscribed to: {}", consumer.subscription())

            try {
                while (isActive) {
                    val records = consumer.poll(Duration.ofMillis(100))
                    for (record in records) {
                        try {
                            when (record.topic()) {
                                kafkaConfig.topics.marketState -> {
                                    val data = mapper.readValue<MarketDataMessage>(record.value())
                                    orderBookCacheService.update(data)
                                }

                                kafkaConfig.topics.tradeOrderEvents -> {
                                    val event = mapper.readValue<TradeOrderEventMessage>(record.value())
                                    tradeCacheService.onTradeEvent(event)
                                }

                                kafkaConfig.topics.marketOhclv -> {
                                    val stats = mapper.readValue<SessionStatisticsMessage>(record.value())
                                    tickerCacheService.update(stats)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Error processing Kafka record from topic {}: {}",
                                record.topic(), e.message, e)
                        }
                    }
                }
            } catch (e: CancellationException) {
                logger.info("Market data consumer cancelled")
            } finally {
                consumer.close()
                logger.info("Market data consumer closed")
            }
        }
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun stop() {
        job?.cancel()
    }
}
