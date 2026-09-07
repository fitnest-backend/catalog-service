package az.fitnest.catalog.util;

public final class GeoDistance {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoDistance() {
    }

    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDistance / 2.0) * Math.sin(latDistance / 2.0)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2.0) * Math.sin(lonDistance / 2.0);
        return EARTH_RADIUS_KM * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    public static Double roundedKm(Double userLat, Double userLng, Double gymLat, Double gymLng) {
        if (userLat == null || userLng == null || gymLat == null || gymLng == null) {
            return null;
        }
        return Math.round(haversineKm(userLat, userLng, gymLat, gymLng) * 10.0) / 10.0;
    }
}
