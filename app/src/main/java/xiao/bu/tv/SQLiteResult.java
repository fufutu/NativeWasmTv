package xiao.bu.tv;

/** 一次查询的结果：列名 + 行数据（值类型为 null / Long / Double / String / byte[]）。 */
final class SQLiteResult {

    private final String[] columns;
    private final Object[][] rows;

    SQLiteResult(String[] columns, Object[][] rows) {
        this.columns = columns == null ? new String[0] : columns;
        this.rows = rows == null ? new Object[0][] : rows;
    }

    int rowCount() {
        return rows.length;
    }

    int columnCount() {
        return columns.length;
    }

    Object[] row(int index) {
        return rows[index];
    }

    Object get(int row, int col) {
        Object[] r = rows[row];
        return (col >= 0 && col < r.length) ? r[col] : null;
    }

    Object get(int row, String column) {
        return get(row, indexOf(column));
    }

    String getString(int row, String column) {
        return asString(get(row, column));
    }

    String getString(int row, int col) {
        return asString(get(row, col));
    }

    long getLong(int row, String column) {
        Object v = get(row, column);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    double getDouble(int row, String column) {
        Object v = get(row, column);
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }

    int indexOf(String column) {
        if (column == null) return -1;
        for (int i = 0; i < columns.length; i++) {
            if (column.equals(columns[i])) return i;
        }
        return -1;
    }

    static String asString(Object v) {
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }
}
