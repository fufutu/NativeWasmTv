cmake_minimum_required(VERSION 3.22.1)
project(sqlitejni C)

# 通用 SQLite 驱动：SQLite 引擎 + 扩展加载 + 结果回传，不含任何搜索业务逻辑。
# 业务（表结构、权重、排序、别名）全部在 Java：xiao/bu/tv/ChannelSearchStore.java。
# JNI 符号绑定的是 Java 类名，所以类所在包必须与 JNI_CLASS_NAME 一致：
#   Java 类 xiao.bu.tv.SQLiteEngine  ->  xiao_bu_tv_SQLiteEngine
set(JNI_CLASS_NAME "xiao_bu_tv_SQLiteEngine" CACHE STRING
    "JNI 绑定的 Java 类全限定名（下划线分隔）")

add_library(sqlitejni SHARED
    sqlite3.c
    sqlite_driver.c
)

target_compile_definitions(sqlitejni PRIVATE
    SQLITE_ENABLE_FTS5
    SQLITE_ENABLE_LOAD_EXTENSION=1
    SQLITE_THREADSAFE=1
    JNI_CLASS_NAME=${JNI_CLASS_NAME}
)

target_link_libraries(sqlitejni PRIVATE dl m)
