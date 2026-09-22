package com.pop110.digital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedFilterTest {
    @Test fun stationaryZero() {
        val f = SpeedFilter()
        repeat(1500) { f.predict(0.0,0.02); if(it%50==0) f.correctGps(0.0,0.3) }
        assertEquals(0.0,f.speed,0.05)
    }
    @Test fun constantAccelerationAndGpsCorrection() {
        val f = SpeedFilter()
        repeat(300) { i -> f.predict(1.0,0.02); if(i%25==0) f.correctGps((i+1)*0.02,0.4) }
        f.correctGps(6.0,0.3)
        assertEquals(6.0,f.speed,0.45)
    }
    @Test fun rejectsSingleGpsSpike() {
        val f = SpeedFilter()
        repeat(100) { f.predict(0.0,0.02) }
        f.correctGps(50.0,0.5)
        assertTrue(f.speed < 4.0)
    }
    @Test fun stationaryNoiseAutoTune() {
        val f = SpeedFilter()
        repeat(500) {f.learnStationaryNoise(0.1)}
        assertTrue(f.processNoise in 0.12..3.0)
        val before = f.processNoise
        repeat(100) {f.learnStationaryNoise(if(it%2==0) 2.0 else -2.0)}
        assertTrue(f.processNoise > before)
    }
    @Test fun clampsNegativeSpeed() {
        val f = SpeedFilter()
        repeat(100) { f.predict(-4.0,0.02) }
        assertEquals(0.0,f.speed,0.0)
    }
}
