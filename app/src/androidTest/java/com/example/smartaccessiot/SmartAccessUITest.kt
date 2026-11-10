package com.example.smartaccessiot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmartAccessUITest {

    @get:Rule
    @JvmField
    val composeTestRule = createComposeRule()

    @Test
    fun testHeaderPrototipo_rendersCorrectly() {
        composeTestRule.setContent {
            HeaderPrototipo()
        }

        composeTestRule.onNodeWithText("prototipo").assertIsDisplayed()
        composeTestRule.onNodeWithText("DEMOSTRACIÓN").assertIsDisplayed()
    }

    @Test
    fun testMetricaItem_rendersValues() {
        composeTestRule.setContent {
            MetricaItem(valor = "6", label = "Puertas Total", color = DarkGreenOT)
        }

        composeTestRule.onNodeWithText("6").assertIsDisplayed()
        composeTestRule.onNodeWithText("Puertas Total").assertIsDisplayed()
    }

    @Test
    fun testItemPuertaFigma_displaysDoorInfoAndTriggersClick() {
        var clicked = false
        val puertaMock = PuertaOT(
            id = "P2-01",
            ubicacion = "Oficinas A",
            estado = "Cerrada",
            conectada = true,
            alerta = null,
        )

        composeTestRule.setContent {
            ItemPuertaFigma(puerta = puertaMock) { clicked = true }
        }

        composeTestRule.onNodeWithText("P2-01").assertIsDisplayed()
        composeTestRule.onNodeWithText("Oficinas A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cerrada").assertIsDisplayed()

        composeTestRule.onNodeWithText("P2-01").performClick()
        assertTrue(clicked)
    }
}
