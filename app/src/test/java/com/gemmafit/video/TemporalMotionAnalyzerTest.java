package com.gemmafit.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class TemporalMotionAnalyzerTest {
    @Test
    public void countsOneSquatRepFromTopBottomTopSequence() {
        TemporalMotionAnalyzer analyzer = new TemporalMotionAnalyzer();
        float[] angles = new float[] {175f, 160f, 130f, 100f, 85f, 105f, 135f, 170f};

        TemporalMotionAnalyzer.Result result = new TemporalMotionAnalyzer.Result();
        for (int i = 0; i < angles.length; i++) {
            result = analyzer.addSample(i, i * 200L, "squat", metrics("knee_angle", angles[i]));
        }

        assertEquals(1, result.getRepCount());
        assertEquals("top", result.getMovementPhase());
        assertNotNull(result.getCompletedRep());
        assertTrue(result.getRangeOfMotionDeg() >= 60f);
    }

    @Test
    public void rapidMovementRequiresConsecutiveSmoothedVelocityFrames() {
        TemporalMotionAnalyzer analyzer = new TemporalMotionAnalyzer();
        float[] angles = new float[] {180f, 120f, 30f, 120f};

        QualityFlag flag = null;
        for (int i = 0; i < angles.length; i++) {
            flag = analyzer
                .addSample(i, i * 100L, "squat", metrics("knee_angle", angles[i]))
                .getRapidFlag();
        }

        assertNotNull(flag);
        assertEquals(6, flag.getRule());
        assertEquals("rapid_movement", flag.getId());
        assertEquals(600f, flag.getThreshold(), 0.01f);
        assertTrue(flag.getValue() > 600f);
    }

    private static Map<String, Float> metrics(String key, float value) {
        Map<String, Float> metrics = new HashMap<>();
        metrics.put(key, value);
        return metrics;
    }
}
