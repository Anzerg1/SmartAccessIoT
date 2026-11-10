package com.example.smartaccessiot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// 1. SEGURIDAD ISO 27400 Y ACCIONES NATIVAS

fun sha256(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

fun ejecutarLlamadaEmergencia(context: Context, numero: String) {
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:$numero")
    }
    context.startActivity(intent)
}

data class UsuarioCredencial(
    val rut: String = "",
    val correo: String = "",
    val passwordHash: String = ""
)


// 2. MODELO DE DATOS Y ESTADO IOT EN NUBE

data class PuertaOT(
    val id: String = "",
    val ubicacion: String = "",
    val estado: String = "Cerrada", // "Cerrada", "Abierta", "Sin datos"
    val conectada: Boolean = true,
    val alerta: String? = null
)

object EdificioAlamedaState {
    private val db = FirebaseDatabase.getInstance().reference

    var nivelIncendio by mutableStateOf("Baja")
    var alarmaActiva by mutableStateOf(false)
    var intentosFallidos by mutableStateOf(0)
    var puertaHardwareSeleccionada by mutableStateOf("P2-04")

    val logAuditoria = mutableStateListOf<String>()
    val usuariosRegistrados = mutableStateListOf<UsuarioCredencial>()

    val puertas = mutableStateListOf(
        PuertaOT("P2-01", "Oficinas A", "Cerrada", true, null),
        PuertaOT("P2-02", "Sala técnica", "Cerrada", true, null),
        PuertaOT("P2-03", "Escalera B", "Cerrada", true, null),
        PuertaOT("P2-04", "Escalera A", "Cerrada", true, null),
        PuertaOT("P2-05", "Oficinas B", "Cerrada", true, null),
        PuertaOT("P2-06", "Archivo", "Sin datos", false, "Sin telemetría")
    )

    fun iniciarSincronizacionCloud() {
        // Inicializar puertas en la nube si la base está vacía
        db.child("puertas").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    puertas.forEach { puerta ->
                        db.child("puertas").child(puerta.id).setValue(puerta)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Escuchar TODAS las puertas en tiempo real y forzar recomposición
        db.child("puertas").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val id = child.child("id").getValue(String::class.java) ?: child.key ?: continue
                    val estado = child.child("estado").getValue(String::class.java) ?: "Cerrada"
                    val ubicacion = child.child("ubicacion").getValue(String::class.java) ?: ""
                    val conectada = child.child("conectada").getValue(Boolean::class.java) ?: true
                    val alerta = child.child("alerta").getValue(String::class.java)

                    val index = puertas.indexOfFirst { it.id == id }
                    if (index != -1) {
                        puertas[index] = PuertaOT(
                            id = id,
                            ubicacion = if (ubicacion.isNotEmpty()) ubicacion else puertas[index].ubicacion,
                            estado = estado,
                            conectada = conectada,
                            alerta = alerta
                        )
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Escuchar sensor de incendio
        db.child("telemetria").child("nivelIncendio").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val nivel = snapshot.getValue(String::class.java) ?: "Baja"
                nivelIncendio = nivel
                alarmaActiva = (nivel == "Alta")
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Escuchar alarma de intrusión
        db.child("seguridad").child("alarmaActiva").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                alarmaActiva = snapshot.getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Escuchar usuarios registrados
        db.child("usuarios").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                usuariosRegistrados.clear()
                for (child in snapshot.children) {
                    val user = child.getValue(UsuarioCredencial::class.java)
                    if (user != null) usuariosRegistrados.add(user)
                }
                if (usuariosRegistrados.isEmpty()) {
                    registrarUsuarioCloud(
                        UsuarioCredencial("12.345.678-9", "operador@inacap.cl", sha256("admin123"))
                    )
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Escuchar logs de auditoría
        db.child("auditoria").limitToLast(15).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                logAuditoria.clear()
                for (child in snapshot.children) {
                    val log = child.getValue(String::class.java)
                    if (log != null) logAuditoria.add(0, log)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    fun registrarUsuarioCloud(user: UsuarioCredencial) {
        val claveSegura = user.rut.replace(".", "").replace("-", "")
        db.child("usuarios").child(claveSegura).setValue(user)
        enviarLogCloud("USUARIO: Nueva credencial registrada para ${user.rut}")
    }

    fun enviarLogCloud(mensaje: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        db.child("auditoria").push().setValue("$hora - $mensaje")
    }

    fun setSensorFuegoCloud(nuevoNivel: String) {
        db.child("telemetria").child("nivelIncendio").setValue(nuevoNivel)
        if (nuevoNivel == "Alta") {
            puertas.filter { it.conectada }.forEach { puerta ->
                db.child("puertas").child(puerta.id).child("estado").setValue("Abierta")
                db.child("puertas").child(puerta.id).child("alerta").setValue("FAIL-SAFE: Evacuación obligatoria")
            }
            db.child("seguridad").child("alarmaActiva").setValue(true)
            enviarLogCloud("EMERGENCIA CRÍTICA: Fuego nivel ALTO. Evacuación masiva en Planta 2.")
        } else {
            puertas.filter { it.conectada }.forEach { puerta ->
                db.child("puertas").child(puerta.id).child("estado").setValue("Cerrada")
                db.child("puertas").child(puerta.id).child("alerta").setValue(null)
            }
            db.child("seguridad").child("alarmaActiva").setValue(false)
            enviarLogCloud("NORMAL: Fuego nivel $nuevoNivel. Cerraduras aseguradas.")
        }
    }

    fun validarAperturaCloud(puertaId: String, rut: String, pin: String): Pair<Boolean, String> {
        if (nivelIncendio == "Alta") {
            return Pair(true, "Apertura forzada por evacuación activa.")
        }

        val usuario = usuariosRegistrados.find {
            it.rut.trim().equals(rut.trim(), ignoreCase = true) ||
                    it.correo.trim().equals(rut.trim(), ignoreCase = true)
        }

        if (usuario == null) {
            intentosFallidos++
            registrarFalloCloud(puertaId)
            return Pair(false, "Usuario o RUT no registrado.")
        }

        if (usuario.passwordHash != sha256(pin)) {
            intentosFallidos++
            registrarFalloCloud(puertaId)
            return Pair(false, "Clave incorrecta. Intento #$intentosFallidos/3")
        }

        intentosFallidos = 0
        db.child("puertas").child(puertaId).child("estado").setValue("Abierta")
        db.child("puertas").child(puertaId).child("alerta").setValue("Autorizada por ${usuario.rut}")
        db.child("seguridad").child("alarmaActiva").setValue(false)
        enviarLogCloud("ACCESO AUTORIZADO: Puerta $puertaId abierta por ${usuario.rut}")
        return Pair(true, "Apertura autorizada exitosamente.")
    }

    private fun registrarFalloCloud(puertaId: String) {
        enviarLogCloud("ACCESO DENEGADO: Intento fallido en $puertaId ($intentosFallidos/3)")
        if (intentosFallidos >= 3) {
            db.child("seguridad").child("alarmaActiva").setValue(true)
            db.child("puertas").child(puertaId).child("alerta").setValue("BLOQUEO POR INTRUSIÓN")
            enviarLogCloud("INTRUSIÓN: Alarma detonada tras 3 fallos en $puertaId.")
        }
    }

    fun bloquearPuertaManualCloud(puertaId: String) {
        if (nivelIncendio != "Alta") {
            db.child("puertas").child(puertaId).child("estado").setValue("Cerrada")
            db.child("puertas").child(puertaId).child("alerta").setValue(null)
            enviarLogCloud("OPERACIÓN: Puerta $puertaId asegurada manualmente.")
        }
    }
}

val DarkGreenOT = Color(0xFF1E2D2F)
val BgLight = Color(0xFFF7F8FA)
val TextMuted = Color(0xFF6B7280)
val DangerRed = Color(0xFFC62828)
val DangerBg = Color(0xFFFFEBEE)
val SuccessGreen = Color(0xFF2E7D32)
val SuccessBg = Color(0xFFE8F5E9)


// 3. ACTIVITY PRINCIPAL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdificioAlamedaState.iniciarSincronizacionCloud()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BgLight) {
                    AppNavegacionPrincipal()
                }
            }
        }
    }
}

@Composable
fun AppNavegacionPrincipal() {
    var pantallaActual by rememberSaveable { mutableStateOf("LOGIN") }
    var pestanaBottom by rememberSaveable { mutableStateOf("RESUMEN") }
    var puertaSeleccionadaId by rememberSaveable { mutableStateOf<String?>(null) }

    fun cerrarSesion() {
        EdificioAlamedaState.enviarLogCloud("LOGOUT: Sesión cerrada")
        puertaSeleccionadaId = null
        pestanaBottom = "RESUMEN"
        pantallaActual = "LOGIN"
    }

    when (pantallaActual) {
        "LOGIN" -> PantallaIngresoUsuario(
            onLoginExitoso = { pantallaActual = "APP" },
            onIrRegistro = { pantallaActual = "REGISTRO" },
            onIrSimulador = { pantallaActual = "SIMULADOR" }
        )
        "REGISTRO" -> PantallaRegistroUsuario(
            onRegistroExitoso = { pantallaActual = "LOGIN" },
            onVolverLogin = { pantallaActual = "LOGIN" }
        )
        "SIMULADOR" -> PantallaSimuladorHardware(
            onVolver = { pantallaActual = "LOGIN" }
        )
        "APP" -> {
            if (puertaSeleccionadaId != null) {
                PantallaDetallePuerta(
                    puertaId = puertaSeleccionadaId!!,
                    onVolver = { puertaSeleccionadaId = null }
                )
            } else {
                Scaffold(
                    bottomBar = {
                        BarraNavegacionFigma(
                            actual = pestanaBottom,
                            onSeleccionar = { pestanaBottom = it }
                        )
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        when (pestanaBottom) {
                            "RESUMEN" -> PantallaResumen(
                                onVerIncendio = { pestanaBottom = "INCENDIO" },
                                onVerPuertas = { pestanaBottom = "PUERTAS" },
                                onCerrarSesion = { cerrarSesion() }
                            )
                            "PUERTAS" -> PantallaListadoPuertas(
                                onSeleccionarPuerta = { puertaSeleccionadaId = it },
                                onCerrarSesion = { cerrarSesion() }
                            )
                            "INCENDIO" -> PantallaIncendio(
                                onCerrarSesion = { cerrarSesion() }
                            )
                        }
                    }
                }
            }
        }
    }
}


// 4. PANTALLA: LOGIN CON VALIDACIÓN REAL

@Composable
fun PantallaIngresoUsuario(
    onLoginExitoso: () -> Unit,
    onIrRegistro: () -> Unit,
    onIrSimulador: () -> Unit
) {
    var rut by rememberSaveable { mutableStateOf("") }
    var clave by rememberSaveable { mutableStateOf("") }
    var mensajeError by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(30.dp))
        HeaderPrototipo()

        Spacer(modifier = Modifier.height(24.dp))
        Text("Ingreso de usuario", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Text("Autenticación segura en la nube (ISO 27400)", color = TextMuted, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Acceso a tu cuenta", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Text("Usuario o RUT", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = rut,
                    onValueChange = { rut = it; mensajeError = "" },
                    placeholder = { Text("Ej: 12.345.678-9") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("Contraseña", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = clave,
                    onValueChange = { clave = it; mensajeError = "" },
                    placeholder = { Text("••••••••") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mensajeError.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(mensajeError, color = DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val usuarioEncontrado = EdificioAlamedaState.usuariosRegistrados.find {
                            it.rut.trim().equals(rut.trim(), ignoreCase = true) ||
                                    it.correo.trim().equals(rut.trim(), ignoreCase = true)
                        }

                        if (usuarioEncontrado == null) {
                            mensajeError = "Usuario no registrado."
                        } else if (usuarioEncontrado.passwordHash != sha256(clave)) {
                            mensajeError = "Contraseña incorrecta."
                        } else {
                            EdificioAlamedaState.enviarLogCloud("LOGIN: Sesión iniciada por ${usuarioEncontrado.rut}")
                            onLoginExitoso()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkGreenOT),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Ingresar  ➔", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("¿No tienes una cuenta?", color = TextMuted)
            TextButton(onClick = onIrRegistro) {
                Text("Crear cuenta", color = DarkGreenOT, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onIrSimulador) {
            Text("⚙️ Modo Terminal / Hardware Sim (Dispositivo 1)", color = DarkGreenOT)
        }
    }
}


// 5. PANTALLA: REGISTRO DE USUARIO

@Composable
fun PantallaRegistroUsuario(onRegistroExitoso: () -> Unit, onVolverLogin: () -> Unit) {
    var correo by rememberSaveable { mutableStateOf("") }
    var rut by rememberSaveable { mutableStateOf("") }
    var clave by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        HeaderPrototipo()

        Spacer(modifier = Modifier.height(16.dp))
        Text("Registro de usuario", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Text("Almacenamiento persistente en Firebase Cloud", color = TextMuted, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Datos de la cuenta", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(14.dp))

                Text("Correo electrónico", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = correo, onValueChange = { correo = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                Spacer(modifier = Modifier.height(10.dp))
                Text("RUT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(value = rut, onValueChange = { rut = it }, placeholder = { Text("12.345.678-9") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                Spacer(modifier = Modifier.height(10.dp))
                Text("Contraseña (mínimo 4 caracteres)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = clave,
                    onValueChange = { clave = it },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = DangerRed, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (rut.isBlank() || correo.isBlank() || clave.length < 4) {
                            error = "Completa todos los campos obligatorios."
                        } else {
                            EdificioAlamedaState.registrarUsuarioCloud(
                                UsuarioCredencial(rut.trim(), correo.trim(), sha256(clave))
                            )
                            onRegistroExitoso()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkGreenOT),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Crear cuenta  ➔", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        TextButton(onClick = onVolverLogin) {
            Text("¿Ya tienes una cuenta? Ingresar", color = DarkGreenOT)
        }
    }
}


// 6. PANTALLA: RESUMEN EDIFICIO

@Composable
fun PantallaResumen(
    onVerIncendio: () -> Unit,
    onVerPuertas: () -> Unit,
    onCerrarSesion: () -> Unit = {}
) {
    val estado = EdificioAlamedaState
    val cerradasCount = estado.puertas.count { it.estado == "Cerrada" }
    val abiertasCount = estado.puertas.count { it.estado == "Abierta" }
    val sinDatosCount = estado.puertas.count { it.estado == "Sin datos" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        HeaderPrototipo(onCerrarSesion = onCerrarSesion)

        Spacer(modifier = Modifier.height(16.dp))
        Text("Resumen", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Edificio Alameda • Sincronizado en tiempo real", color = TextMuted, fontSize = 14.sp)

        Spacer(modifier = Modifier.height(20.dp))

        if (estado.nivelIncendio == "Alta" || estado.alarmaActiva) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onVerIncendio() },
                colors = CardDefaults.cardColors(containerColor = DangerBg),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🚨", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Incidente activo", color = DangerRed, fontWeight = FontWeight.Bold)
                        Text(
                            if (estado.nivelIncendio == "Alta") "Planta 2 • Evacuación Fail-Safe"
                            else "Planta 2 • Alerta de Intrusión activa",
                            color = DangerRed,
                            fontSize = 13.sp
                        )
                    }
                    Text("➔", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("6 puertas supervisadas en Planta 2", fontWeight = FontWeight.Bold)
                    Text("En línea", color = SuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    MetricaItem(valor = cerradasCount.toString(), label = "Cerradas", color = SuccessGreen)
                    MetricaItem(valor = abiertasCount.toString(), label = "Abiertas", color = if (abiertasCount > 0) DangerRed else TextMuted)
                    MetricaItem(valor = sinDatosCount.toString(), label = "Sin datos", color = Color.Gray)
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFFEEEEEE))
                Spacer(modifier = Modifier.height(12.dp))

                Text("📶 Wi-Fi / Firebase RTDB en vivo", color = TextMuted, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Estado por planta", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))

        PlantaCard("PB", "Planta baja", "6 cerradas", SuccessGreen, onClick = {})
        PlantaCard("P1", "Planta 1", "6 cerradas", SuccessGreen, onClick = {})
        PlantaCard(
            "P2", "Planta 2",
            if (abiertasCount > 0 || estado.alarmaActiva) "$abiertasCount abierta(s)" else "Normal",
            if (abiertasCount > 0 || estado.alarmaActiva) DangerRed else SuccessGreen,
            onClick = onVerPuertas
        )
        PlantaCard("P3", "Planta 3", "6 cerradas", SuccessGreen, onClick = {})

        Spacer(modifier = Modifier.height(20.dp))
        Text("Registro de Auditoría (Trazabilidad OT):", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState())) {
                estado.logAuditoria.forEach { log ->
                    Text(log, fontSize = 11.sp, color = DarkGreenOT)
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        OutlinedButton(
            onClick = onCerrarSesion,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("🚪 Cerrar Sesión y Volver al Menú", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(modifier = Modifier.height(30.dp))
    }
}


// 7. PANTALLA: LISTADO DE PUERTAS

@Composable
fun PantallaListadoPuertas(
    onSeleccionarPuerta: (String) -> Unit,
    onCerrarSesion: () -> Unit = {}
) {
    val estado = EdificioAlamedaState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        HeaderPrototipo(onCerrarSesion = onCerrarSesion)

        Spacer(modifier = Modifier.height(16.dp))
        Text("Puertas", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Edificio Alameda • Pulsa cualquier puerta para operar", color = TextMuted, fontSize = 14.sp)

        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(estado.puertas) { puerta ->
                ItemPuertaFigma(
                    puerta = puerta,
                    onAccionClick = {
                        if (puerta.conectada) {
                            onSeleccionarPuerta(puerta.id)
                        }
                    }
                )
                HorizontalDivider(color = Color(0xFFEEEEEE))
            }
        }
    }
}


// 8. PANTALLA: DETALLE DE PUERTA

@Composable
fun PantallaDetallePuerta(puertaId: String, onVolver: () -> Unit) {
    val estado = EdificioAlamedaState
    val puerta = estado.puertas.firstOrNull { it.id == puertaId }
        ?: PuertaOT(id = puertaId, ubicacion = "", estado = "Cerrada", conectada = true)
    var mostrarDialogo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onVolver) { Text("⬅ Volver", color = DarkGreenOT) }
            Text(puerta.id, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth().height(200.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (puerta.estado == "Abierta") SuccessGreen else DangerRed
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (puerta.estado == "Abierta") "🔓" else "🔒",
                    fontSize = 60.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "ESTADO: ${puerta.estado.uppercase()}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                Text(
                    text = puerta.ubicacion,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (puerta.alerta != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DangerBg)
            ) {
                Text(
                    text = "⚠️ ${puerta.alerta}",
                    color = DangerRed,
                    modifier = Modifier.padding(14.dp),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (puerta.estado == "Cerrada") {
            Button(
                onClick = { mostrarDialogo = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkGreenOT),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Ingresar Credencial de Apertura  ➔", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { estado.bloquearPuertaManualCloud(puerta.id) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Bloquear Cerradura Remotamente", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (mostrarDialogo) {
        DialogoCredencialApertura(
            puertaId = puerta.id,
            onDismiss = { mostrarDialogo = false },
            onAutorizado = { mostrarDialogo = false }
        )
    }
}


// 9. PANTALLA: INCENDIO CON BOTÓN DE LLAMADA

@Composable
fun PantallaIncendio(onCerrarSesion: () -> Unit = {}) {
    val estado = EdificioAlamedaState
    val context = LocalContext.current

    // Configura tu número real de emergencia aquí (ej: "+56912345678")
    val numeroEmergencia = "+56941089033"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeaderPrototipo(onCerrarSesion = onCerrarSesion)

        Spacer(modifier = Modifier.height(16.dp))
        Text("Incendio y Fail-Safe", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Text("Telemetría en tiempo real y respuesta ante emergencias", color = TextMuted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(20.dp))

        // --- BOTÓN DE LLAMADA TELEFÓNICA DIRECTA ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DangerBg),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🚨 Central de Enlace y Emergencia", fontWeight = FontWeight.Bold, color = DangerRed)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Marcado rápido directo con la central de seguridad para incidentes críticos.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        ejecutarLlamadaEmergencia(context, numeroEmergencia)
                        estado.enviarLogCloud("EMERGENCIA: Llamada telefónica iniciada hacia $numeroEmergencia")
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("📞 Llamar a Seguridad ($numeroEmergencia)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- SENSOR DE FLAMA OT ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Sensor Óptico de Flama OT", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(54.dp).background(DangerBg, shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔥", fontSize = 28.sp)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            estado.nivelIncendio,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (estado.nivelIncendio == "Alta") DangerRed else Color.Black
                        )
                        Text(
                            if (estado.nivelIncendio == "Alta") "Evacuación masiva activa" else "Nivel normal",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("Baja", "Media", "Alta").forEach { nivel ->
                        Button(
                            onClick = { estado.setSensorFuegoCloud(nivel) },
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (estado.nivelIncendio == nivel) DangerRed else Color(0xFFE0E0E0)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                nivel,
                                color = if (estado.nivelIncendio == nivel) Color.White else Color.Black,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}


// 10. DIÁLOGO: CREDENCIAL DE APERTURA (ISO 27400)

@Composable
fun DialogoCredencialApertura(puertaId: String, onDismiss: () -> Unit, onAutorizado: () -> Unit) {
    var rut by remember { mutableStateOf("12.345.678-9") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("Credencial: $puertaId", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Ingresa credenciales para autorizar apertura.", color = TextMuted, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = rut,
                    onValueChange = { rut = it; error = "" },
                    label = { Text("Usuario o RUT registrado") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it; error = "" },
                    label = { Text("PIN / Clave (admin123)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val resultado = EdificioAlamedaState.validarAperturaCloud(puertaId, rut, pin)
                        if (resultado.first) {
                            onAutorizado()
                        } else {
                            error = resultado.second
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkGreenOT),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Validar credencial  ➔")
                }

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar solicitud", color = TextMuted)
                }
            }
        }
    )
}


// 11. MODO SIMULADOR DE HARDWARE (DISPOSITIVO 1)
@Composable
fun PantallaSimuladorHardware(onVolver: () -> Unit) {
    val estado = EdificioAlamedaState
    val puertaActual = estado.puertas.firstOrNull { it.id == estado.puertaHardwareSeleccionada }
        ?: PuertaOT(id = estado.puertaHardwareSeleccionada, ubicacion = "", estado = "Cerrada")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Simulador de Terminal OT", fontWeight = FontWeight.Bold)
            Button(
                onClick = onVolver,
                colors = ButtonDefaults.buttonColors(containerColor = DarkGreenOT),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("⬅ Menú Principal", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Puerta física simulada por este equipo:", fontSize = 13.sp, color = TextMuted)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("P2-01", "P2-02", "P2-03", "P2-04").forEach { pid ->
                Button(
                    onClick = { estado.puertaHardwareSeleccionada = pid },
                    modifier = Modifier.weight(1f).padding(2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (estado.puertaHardwareSeleccionada == pid) DarkGreenOT else Color(0xFFE0E0E0)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        pid,
                        color = if (estado.puertaHardwareSeleccionada == pid) Color.White else Color.Black,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (puertaActual.estado == "Abierta") SuccessGreen else DangerRed
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (puertaActual.estado == "Abierta") "🔓" else "🔒",
                    fontSize = 50.sp
                )
                Text(
                    text = "${puertaActual.id}: ${if (puertaActual.estado == "Abierta") "ABIERTA" else "BLOQUEADA"}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = puertaActual.ubicacion,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (estado.alarmaActiva) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF5722))
            ) {
                Text(
                    "🚨 SIRENA DE PLANTA 2 ACTIVA 🚨\n(Causa: Intrusión o Incendio)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        Button(
            onClick = {
                if (puertaActual.estado == "Abierta") {
                    estado.bloquearPuertaManualCloud(puertaActual.id)
                } else {
                    FirebaseDatabase.getInstance().reference.child("puertas").child(puertaActual.id).child("estado").setValue("Abierta")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (puertaActual.estado == "Abierta") "Cerrar Pestillo Físico" else "Abrir Pestillo Físico")
        }
    }
}


// COMPONENTES REUTILIZABLES UI

@Composable
fun HeaderPrototipo(onCerrarSesion: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🛡️", fontSize = 18.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text("prototipo", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onCerrarSesion != null) {
                Surface(
                    color = DangerBg,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clickable { onCerrarSesion() }
                ) {
                    Text(
                        "🚪 Salir",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = DangerRed
                    )
                }
            }
            Surface(color = Color(0xFFEEEEEE), shape = RoundedCornerShape(6.dp)) {
                Text(
                    "DEMOSTRACIÓN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
fun MetricaItem(valor: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valor, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
fun PlantaCard(codigo: String, nombre: String, estado: String, estadoColor: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFFEEEEEE), shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(codigo, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(nombre, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(estado, color = estadoColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("➔", color = TextMuted)
        }
    }
}

@Composable
fun ItemPuertaFigma(puerta: PuertaOT, onAccionClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAccionClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(puerta.id, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(puerta.ubicacion, color = TextMuted, fontSize = 14.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (puerta.conectada) "📶 Conectada" else "📵 Sin conexión • 09:37",
                color = TextMuted,
                fontSize = 12.sp
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = when (puerta.estado) {
                    "Abierta" -> DangerBg
                    "Cerrada" -> SuccessBg
                    else -> Color(0xFFFFF3E0)
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    puerta.estado,
                    color = when (puerta.estado) {
                        "Abierta" -> DangerRed
                        "Cerrada" -> SuccessGreen
                        else -> Color(0xFFE65100)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            if (puerta.alerta != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(puerta.alerta, color = DangerRed, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun BarraNavegacionFigma(actual: String, onSeleccionar: (String) -> Unit) {
    NavigationBar(containerColor = Color.White) {
        NavigationBarItem(
            selected = actual == "RESUMEN",
            onClick = { onSeleccionar("RESUMEN") },
            icon = { Text("📊", fontSize = 18.sp) },
            label = { Text("Resumen", fontSize = 11.sp) }
        )
        NavigationBarItem(
            selected = actual == "PUERTAS",
            onClick = { onSeleccionar("PUERTAS") },
            icon = { Text("🚪", fontSize = 18.sp) },
            label = { Text("Puertas", fontSize = 11.sp) }
        )
        NavigationBarItem(
            selected = actual == "INCENDIO",
            onClick = { onSeleccionar("INCENDIO") },
            icon = { Text("🔥", fontSize = 18.sp) },
            label = { Text("Incendio", fontSize = 11.sp) }
        )
    }
}