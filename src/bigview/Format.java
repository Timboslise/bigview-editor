package bigview;

final class Format {

    private Format() {
    }

    static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = n;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format("%.1f %s", v, units[u]);
    }

    static String number(long n) {
        return String.format("%,d", n);
    }
}
