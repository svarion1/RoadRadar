import org.junit.Test
import org.junit.Assert.assertEquals

class SpeedCalculatorIntegrationTest {
    @Test
    fun testSpeedCalculation() {
        val distance = 100.0 // in meters
        val time = 10.0 // in seconds
        val expectedSpeed = 36.0 // in km/h

        val calculatedSpeed = calculateSpeed(distance, time)
        assertEquals(expectedSpeed, calculatedSpeed, 0.01)
    }

    private fun calculateSpeed(distance: Double, time: Double): Double {
        return if (time > 0) {
            (distance / time) * 3.6 // converting m/s to km/h
        } else {
            0.0
        }
    }
}