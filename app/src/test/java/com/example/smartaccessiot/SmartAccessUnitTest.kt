package com.example.smartaccessiot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartAccessUnitTest {

    @Test
    fun testSha256Hashing_validInput() {
        val input = "admin123"
        val hash = sha256(input)

        // SHA-256 output is 64 hexadecimal characters
        assertEquals(64, hash.length)
        // Verify deterministic output
        assertEquals(sha256(input), hash)
        // Verify different inputs produce different hashes
        assertNotEquals(hash, sha256("admin124"))
    }

    @Test
    fun testSha256Hashing_knownVector() {
        // Known SHA-256 hash for empty string ""
        val emptyHash = sha256("")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash)
    }

    @Test
    fun testUsuarioCredencial_creationAndDefaults() {
        val usuarioDefault = UsuarioCredencial()
        assertEquals("", usuarioDefault.rut)
        assertEquals("", usuarioDefault.correo)
        assertEquals("", usuarioDefault.passwordHash)

        val rut = "12.345.678-9"
        val correo = "operador@inacap.cl"
        val passHash = sha256("admin123")

        val usuario = UsuarioCredencial(
            rut = rut,
            correo = correo,
            passwordHash = passHash,
        )

        assertEquals(rut, usuario.rut)
        assertEquals(correo, usuario.correo)
        assertEquals(passHash, usuario.passwordHash)
    }

    @Test
    fun testPuertaOT_creationAndStateChange() {
        val puerta = PuertaOT(
            id = "P2-01",
            ubicacion = "Oficinas A",
            estado = "Cerrada",
            conectada = true,
            alerta = null,
        )

        assertEquals("P2-01", puerta.id)
        assertEquals("Oficinas A", puerta.ubicacion)
        assertEquals("Cerrada", puerta.estado)
        assertTrue(puerta.conectada)
        assertNull(puerta.alerta)

        // Probar cambio de estado (Simulación de apertura / Fail-Safe)
        val puertaAbierta = puerta.copy(
            estado = "Abierta",
            alerta = "FAIL-SAFE: Evacuación obligatoria",
        )

        assertEquals("Abierta", puertaAbierta.estado)
        assertEquals("FAIL-SAFE: Evacuación obligatoria", puertaAbierta.alerta)
    }
}
