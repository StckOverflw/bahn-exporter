package net.stckoverflw.bahnexporter

import com.influxdb.client.kotlin.InfluxDBClientKotlinFactory
import dev.inmo.krontab.doInfinity
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import net.stckoverflw.bahnexporter.config.Config
import net.stckoverflw.bahnexporter.model.IrisAbfahrten
import net.stckoverflw.bahnexporter.util.delayPoint
import net.stckoverflw.bahnexporter.util.mapToDelay
import net.stckoverflw.bahnexporter.util.mapToDepartureOrArrival
import net.stckoverflw.bahnexporter.util.planPoint
import net.stckoverflw.bahnexporter.util.trainCountPoint

val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
        })
    }
}

private val LOG = KotlinLogging.logger { }

suspend fun main() {
    Config.load()

    val client = InfluxDBClientKotlinFactory
        .create(
            Config.configuration.influxAddress,
            Config.configuration.influxToken.toCharArray(),
            Config.configuration.influxOrg,
            Config.configuration.influxBucket
        )
    val writeApi = client.getWriteKotlinApi()

    val targets = Config.configuration.targets

    val kronTabSchedule = "0 */${Config.configuration.interval} * * * *"

    val trainRegex = Regex(Config.configuration.trainRegex)

    coroutineScope {
        LOG.info { "Start Collecting Bahn Data every ${Config.configuration.interval} minute(s)" }
        doInfinity(kronTabSchedule) { time ->
            LOG.info { "Collecting Bahn Data at ${time.date} ${time.time}" }
            targets.forEach { target ->
                LOG.debug { "Collecting for ${target.id} (${target.name})" }
                val irisAbfahrten = httpClient.get(Config.configuration.baseApiUrl + "/iris/v2/abfahrten/${target.id}") {
                    parameter("lookbehind", Config.configuration.lookbehind)
                    parameter("lookahead", Config.configuration.lookahead)
                }.body<IrisAbfahrten>()

                val departures = (irisAbfahrten.departures + irisAbfahrten.lookbehind).filter { trainRegex.matches(it.train.type) }

                val delay = departures.mapToDelay()

                val delaysAboveZero = delay.filter { it > 0 }

                val minDelay = if (delaysAboveZero.isNotEmpty()) delaysAboveZero.minOf { it } else 0
                val maxDelay = if (delay.isNotEmpty()) delay.maxOf { it } else 0
                val delaySum = delay.sum()
                val averageDelay = delaySum.toDouble() / delay.count { it > 0 }

                writeApi.writePoint(delayPoint(target.name, minDelay, maxDelay, delaySum, averageDelay))

                val cancellations = departures.count { it.cancelled }
                val notPlannedSequences = departures.count { !it.sameSequence }
                val notPlannedPlatform = departures.mapToDepartureOrArrival().count { it.scheduledPlatform != it.platform }

                writeApi.writePoint(planPoint(target.name, cancellations, notPlannedSequences, notPlannedPlatform))

                writeApi.writePoint(trainCountPoint(target.name, departures.count()))
            }
        }
    }
}
