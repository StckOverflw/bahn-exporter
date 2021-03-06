package net.stckoverflw.bahnexporter.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

object Config {

    lateinit var configuration: Configuration

    private val json = Json {
        prettyPrint = true
    }

    fun load() {
        val path = Path("./exporter.json")
        if (!path.exists() || path.readText().isEmpty()) {
            path.writeText(
                json.encodeToString(
                    Configuration(
                        "", "", "", "", 10,
                        listOf(TargetConfig("Frankfurt(Main)Hbf", "8000105"))
                    )
                )
            )
            error("Please fill the newly created config file exorter.json and restart the application")
        }
        configuration = Json.decodeFromString(path.readText())
    }

}

@Serializable
data class Configuration(
    @SerialName("influx_address") val influxAddress: String,
    @SerialName("influx_token") val influxToken: String,
    @SerialName("influx_org") val influxOrg: String,
    @SerialName("influx_bucket") val influxBucket: String,
    val interval: Int,
    val targets: List<TargetConfig>,
    val lookbehind: Int = 10,
    val lookahead: Int = 10,
    @SerialName("train_regex") val trainRegex: String = "ICE|RE|IC|IRE|RB|EC|ECE",
    @SerialName("base_api_url") val baseApiUrl: String = "https://marudor.de/api",
)

@Serializable
data class TargetConfig(
    val name: String,
    val id: String
)
