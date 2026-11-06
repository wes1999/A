package com.wes1999.luzsolars24;

import java.util.Calendar;
import java.util.Locale;

public final class SunMath {
    private SunMath() {}
    public static final double[] LIMITS = {-18.0, -12.0, -6.0, -0.833};
    public static final String[] PHASES = {"Astronômico", "Náutico", "Civil", "Nascer / pôr do Sol"};

    public static final class Plan {
        public final double[] dawn = {-1,-1,-1,-1};
        public final double[] dusk = {-1,-1,-1,-1};
        public boolean valid() { return dawn[0] >= 0 && dawn[3] >= 0 && dusk[3] >= 0 && dusk[0] >= 0; }
    }

    public static final class Light {
        public final int kelvin;
        public final double brightness;
        public Light(int kelvin, double brightness) {
            this.kelvin = kelvin;
            this.brightness = Math.max(0, Math.min(100, brightness));
        }
    }

    public static double sunAltitude(Calendar at, double lat, double lon) {
        final double rad = Math.PI / 180.0;
        final double hour = at.get(Calendar.HOUR_OF_DAY)
            + at.get(Calendar.MINUTE) / 60.0
            + at.get(Calendar.SECOND) / 3600.0;
        final int day = at.get(Calendar.DAY_OF_YEAR);
        final double gamma = 2 * Math.PI / 365.0 * (day - 1 + (hour - 12.0) / 24.0);
        final double equation = 229.18 * (0.000075
            + 0.001868 * Math.cos(gamma) - 0.032077 * Math.sin(gamma)
            - 0.014615 * Math.cos(2 * gamma) - 0.040849 * Math.sin(2 * gamma));
        final double declination = 0.006918 - 0.399912 * Math.cos(gamma)
            + 0.070257 * Math.sin(gamma) - 0.006758 * Math.cos(2 * gamma)
            + 0.000907 * Math.sin(2 * gamma) - 0.002697 * Math.cos(3 * gamma)
            + 0.00148 * Math.sin(3 * gamma);
        final double zoneMinutes = at.getTimeZone().getOffset(at.getTimeInMillis()) / 60000.0;
        double solarMinutes = (hour * 60 + equation + 4.0 * lon - zoneMinutes) % 1440.0;
        if (solarMinutes < 0) solarMinutes += 1440;
        double hourAngle = (solarMinutes / 4.0 - 180.0) * rad;
        double sinElevation = Math.sin(lat * rad) * Math.sin(declination)
            + Math.cos(lat * rad) * Math.cos(declination) * Math.cos(hourAngle);
        return Math.asin(Math.max(-1, Math.min(1, sinElevation))) / rad;
    }

    public static double altitudeAtMinute(Calendar day, double minute, double lat, double lon) {
        Calendar c = (Calendar) day.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.setTimeInMillis(c.getTimeInMillis() + Math.round(minute * 60000.0));
        return sunAltitude(c, lat, lon);
    }

    public static Plan events(Calendar day, double lat, double lon) {
        Plan out = new Plan();
        double prev = altitudeAtMinute(day, 0, lat, lon);
        for (int minute = 1; minute <= 1440; minute++) {
            double next = altitudeAtMinute(day, minute, lat, lon);
            for (int j = 0; j < LIMITS.length; j++) {
                double level = LIMITS[j];
                if (prev < level && next >= level && out.dawn[j] < 0) {
                    out.dawn[j] = (minute - 1) + (level - prev) / Math.max(1e-9, next-prev);
                }
                if (prev >= level && next < level && out.dusk[j] < 0) {
                    out.dusk[j] = (minute - 1) + (prev - level) / Math.max(1e-9, prev-next);
                }
            }
            prev = next;
        }
        return out;
    }

    public static double altitudeCustom(Calendar day, double virtualMinute, double lat, double lon,
                                        Plan plan, int dawnStart, int duskStart) {
        if (!plan.valid()) return altitudeAtMinute(day, virtualMinute, lat, lon);
        double start = dawnStart;
        double sunset = duskStart;
        if (sunset <= start) sunset += 1440;
        double t = virtualMinute;
        if (t < start) t += 1440;
        double naturalMorning = plan.dawn[3] - plan.dawn[0];
        double naturalEvening = plan.dusk[0] - plan.dusk[3];
        double sunrise = start + naturalMorning;
        double endOfTwilight = sunset + naturalEvening;
        if (sunrise >= sunset || endOfTwilight >= start + 1440 || t > endOfTwilight) return -23;
        double mapped;
        if (t < sunrise) mapped = plan.dawn[0] + (t - start);
        else if (t < sunset) {
            double fraction = (t - sunrise) / Math.max(1, sunset - sunrise);
            mapped = plan.dawn[3] + fraction * (plan.dusk[3] - plan.dawn[3]);
        } else mapped = plan.dusk[3] + (t - sunset);
        return altitudeAtMinute(day, mapped, lat, lon);
    }

    public static Light lightForAltitude(double altitude) {
        double[] h = {-90, -18, -12, -6, -0.833, 10, 30, 60, 90};
        double[] brightness = {0, 0, 3, 13, 32, 65, 90, 100, 100};
        int[] kelvin = {1900, 1900, 2100, 2500, 2900, 4400, 5700, 6500, 6500};
        for (int i = 1; i < h.length; i++) {
            if (altitude <= h[i]) {
                double p = Math.max(0, Math.min(1, (altitude-h[i-1]) / (h[i]-h[i-1])));
                p = p*p*(3-2*p); // transição suave
                return new Light((int)Math.round(kelvin[i-1] + (kelvin[i]-kelvin[i-1])*p),
                    brightness[i-1] + (brightness[i]-brightness[i-1])*p);
            }
        }
        return new Light(6500, 100);
    }

    public static int colorForKelvin(int kelvin) {
        double t = Math.max(1000, Math.min(40000, kelvin)) / 100.0;
        double r = t <= 66 ? 255 : 329.698727446 * Math.pow(t - 60, -0.1332047592);
        double g = t <= 66 ? 99.4708025861 * Math.log(t) - 161.1195681661
            : 288.1221695283 * Math.pow(t - 60, -0.0755148492);
        double b = t >= 66 ? 255 : t <= 19 ? 0
            : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        return android.graphics.Color.rgb(clamp(r),clamp(g),clamp(b));
    }
    private static int clamp(double n) { return (int)Math.max(0,Math.min(255,Math.round(n))); }
    public static String clock(double minutes) {
        if (minutes < 0 || Double.isNaN(minutes)) return "--:--";
        int seconds = (int)Math.round(minutes * 60);
        seconds = ((seconds % 86400) + 86400) % 86400;
        return String.format(Locale.forLanguageTag("pt-BR"), "%02d:%02d:%02d",
            seconds/3600, (seconds/60)%60, seconds%60);
    }
    public static int parseClock(String s, int fallback) {
        try {
            String[] a = s.trim().split(":");
            int h = Integer.parseInt(a[0]), m = Integer.parseInt(a[1]);
            if(h<0 || h>23 || m<0 || m>59) return fallback;
            return h * 60 + m;
        } catch(Exception ignored) { return fallback; }
    }
}