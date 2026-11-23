package xiao.bu.tv;

/**
 * 通用 SQLite 引擎封装（Java 侧驱动）。
 *
 * 这一层不含任何业务知识：打开数据库、加载扩展、执行 SQL、取结果。
 * 表结构在 assets/search/schema.sql，业务在 {@link ChannelSearchIndex}。
 * 换表、加字段、改排序都改 Java 与 schema，原生层一行不用动。
 *
 * 原生库 libsqlitejni.so 的 JNI 符号绑定的是本类名（见 src/main/cpp/CMakeLists.txt
 * 的 JNI_CLASS_NAME=xiao_bu_tv_SQLiteEngine），所以本类不要改名字或改包。
 */
final class SQLiteEngine implements AutoCloseable {

    private static final boolean LIB_LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("sqlitejni");
            ok = true;
        } catch (Throwable t) {
            // 非 arm32 变体没有这个 so，或宿主环境测试自行 System.load，这里只记录状态
            ok = false;
        }
        LIB_LOADED = ok;
    }

    static boolean isLibraryLoaded() {
        return LIB_LOADED;
    }

    private long handle;

    private SQLiteEngine(long handle) {
        this.handle = handle;
    }

    static SQLiteEngine open(String dbPath) {
        long h = nativeOpen(dbPath);
        if (h == 0) throw new IllegalStateException("无法打开数据库: " + dbPath);
        return new SQLiteEngine(h);
    }

    /** 加载 SQLite 扩展；soPath 必须是文件系统上的绝对路径。 */
    void loadExtension(String soPath, String entryPoint) {
        if (handle == 0) throw new IllegalStateException("数据库已关闭");
        nativeLoadExtension(handle, soPath, entryPoint);
    }

    /** 执行多语句脚本（DDL / PRAGMA / BEGIN...COMMIT）。 */
    void exec(String sql) {
        if (handle == 0) throw new IllegalStateException("数据库已关闭");
        nativeExec(handle, sql);
    }

    SQLiteResult query(String sql, Object... args) {
        return queryLimited(-1, sql, args);
    }

    SQLiteResult queryLimited(int maxRows, String sql, Object... args) {
        if (handle == 0) throw new IllegalStateException("数据库已关闭");
        Object[] raw = nativeQuery(handle, sql, args, maxRows);
        if (raw == null) throw new IllegalStateException("查询失败: " + sql);
        return new SQLiteResult((String[]) raw[0], (Object[][]) raw[1]);
    }

    Object queryScalar(String sql, Object... args) {
        SQLiteResult r = queryLimited(1, sql, args);
        return r.rowCount() == 0 ? null : r.get(0, 0);
    }

    boolean isOpen() {
        return handle != 0;
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
    }

    private static native long nativeOpen(String dbPath);

    private static native void nativeClose(long handle);

    private static native void nativeLoadExtension(long handle, String soPath, String entryPoint);

    private static native int nativeExec(long handle, String sql);

    private static native Object[] nativeQuery(long handle, String sql, Object[] args, int maxRows);
}
