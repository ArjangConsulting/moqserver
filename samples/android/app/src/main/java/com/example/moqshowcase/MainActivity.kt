package com.example.moqshowcase

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("moqserver Demo") }) },
                    modifier = Modifier.fillMaxSize()
                ) { padding ->
                    ShowcaseScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    )
                }
            }
        }
    }
}

private enum class Variant(val headerValue: String?) {
    DEFAULT(null),
    ERROR_500("error-500"),
    ERROR_503("error-503")
}

private data class ApiResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String
)

private class MoqServerRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun get(
        baseUrl: String,
        path: String,
        variant: Variant,
        bearerToken: String? = null
    ): ApiResponse = withContext(Dispatchers.IO) {
        val url = normalizeBaseUrl(baseUrl) + path
        val requestBuilder = Request.Builder().url(url)

        variant.headerValue?.let { requestBuilder.header("X-Mock-Variant", it) }
        if (!bearerToken.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $bearerToken")
        }

        client.newCall(requestBuilder.get().build()).execute().use { response ->
            ApiResponse(
                statusCode = response.code,
                headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") },
                body = response.body?.string().orEmpty()
            )
        }
    }

    suspend fun requestToken(baseUrl: String, clientId: String, clientSecret: String): ApiResponse = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val request = Request.Builder()
            .url(normalizeBaseUrl(baseUrl) + "/_auth/token")
            .post(formBody)
            .build()

        client.newCall(request).execute().use { response ->
            ApiResponse(
                statusCode = response.code,
                headers = response.headers.toMultimap().mapValues { it.value.joinToString(",") },
                body = response.body?.string().orEmpty()
            )
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): String =
        if (baseUrl.endsWith('/')) baseUrl.dropLast(1) else baseUrl
}

private class ShowcaseViewModel : ViewModel() {
    var baseUrl by mutableStateOf("http://10.0.2.2:8080")
    var variant by mutableStateOf(Variant.DEFAULT)
    var clientId by mutableStateOf("sample-client")
    var clientSecret by mutableStateOf("sample-secret")
    var token by mutableStateOf("")
    var output by mutableStateOf("Ready. Start moqserver and make a request.")
    var isLoading by mutableStateOf(false)

    private val repository = MoqServerRepository()

    fun fetchHealth() {
        perform("GET /public/health") {
            repository.get(baseUrl = baseUrl, path = "/public/health", variant = Variant.DEFAULT)
        }
    }

    fun fetchPets() {
        perform("GET /pets") {
            repository.get(baseUrl = baseUrl, path = "/pets", variant = variant)
        }
    }

    fun fetchFavorites() {
        perform("GET /pets/favorites") {
            repository.get(
                baseUrl = baseUrl,
                path = "/pets/favorites",
                variant = Variant.DEFAULT,
                bearerToken = token
            )
        }
    }

    fun requestToken() {
        isLoading = true
        viewModelScope.launch {
            try {
                val response = repository.requestToken(baseUrl, clientId, clientSecret)
                val parsedToken = parseAccessToken(response.body)
                if (!parsedToken.isNullOrBlank()) {
                    token = parsedToken
                }

                output = """
                    Request: POST /_auth/token
                    Status: ${response.statusCode}
                    Access token: ${token.ifBlank { "<none returned>" }}

                    Body:
                    ${response.body}
                """.trimIndent()
            } catch (error: Exception) {
                output = "Token request failed:\n${error.message.orEmpty()}"
            } finally {
                isLoading = false
            }
        }
    }

    private fun perform(label: String, request: suspend () -> ApiResponse) {
        isLoading = true
        viewModelScope.launch {
            try {
                val response = request()
                output = """
                    Request: $label
                    Variant: ${variant.name}
                    Status: ${response.statusCode}
                    Headers: ${response.headers}

                    Body:
                    ${response.body}
                """.trimIndent()
            } catch (error: Exception) {
                output = "$label failed:\n${error.message.orEmpty()}"
            } finally {
                isLoading = false
            }
        }
    }

    private fun parseAccessToken(jsonBody: String): String? {
        return runCatching { JSONObject(jsonBody).optString("access_token", "") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

@Composable
private fun ShowcaseScreen(
    modifier: Modifier = Modifier,
    viewModel: ShowcaseViewModel = viewModel()
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = viewModel.baseUrl,
                    onValueChange = { viewModel.baseUrl = it },
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("Variant for /pets")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = viewModel.variant == Variant.DEFAULT,
                        onClick = { viewModel.variant = Variant.DEFAULT },
                        label = { Text("default") }
                    )
                    FilterChip(
                        selected = viewModel.variant == Variant.ERROR_500,
                        onClick = { viewModel.variant = Variant.ERROR_500 },
                        label = { Text("error-500") }
                    )
                    FilterChip(
                        selected = viewModel.variant == Variant.ERROR_503,
                        onClick = { viewModel.variant = Variant.ERROR_503 },
                        label = { Text("error-503") }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OAuth", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = viewModel.clientId,
                    onValueChange = { viewModel.clientId = it },
                    label = { Text("Client ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = viewModel.clientSecret,
                    onValueChange = { viewModel.clientSecret = it },
                    label = { Text("Client Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = viewModel.token,
                    onValueChange = { viewModel.token = it },
                    label = { Text("Access Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row {
                    Button(onClick = { viewModel.requestToken() }, enabled = !viewModel.isLoading) {
                        Text("Request Token")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Requests", style = MaterialTheme.typography.titleMedium)
                Row {
                    Button(onClick = { viewModel.fetchHealth() }, enabled = !viewModel.isLoading) {
                        Text("GET /public/health")
                    }
                }
                Row {
                    Button(onClick = { viewModel.fetchPets() }, enabled = !viewModel.isLoading) {
                        Text("GET /pets")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { viewModel.fetchFavorites() }, enabled = !viewModel.isLoading) {
                        Text("GET /pets/favorites")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Response", style = MaterialTheme.typography.titleMedium)
                if (viewModel.isLoading) {
                    Text("Loading...")
                }
                Text(
                    text = viewModel.output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
