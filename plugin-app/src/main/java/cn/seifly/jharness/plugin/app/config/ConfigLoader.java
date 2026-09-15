package cn.seifly.jharness.plugin.app.config;


import cn.seifly.jharness.plugin.framework.config.Config;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * TinyClaw 配置加载器，支持从 JSON 文件和环境变量加载配置。
 * <p>
 * 核心功能：
 * - 从 JSON 文件加载配置（支持默认路径和自定义路径）
 * - 从环境变量覆盖配置（优先级高于 JSON 文件）
 * - 支持 .env 文件配置
 * - 保存配置到 JSON 文件
 * - 路径处理（~扩展为用户主目录）
 * <p>
 * 配置优先级：
 * 1. 系统环境变量（最高优先级）
 * 2. .env 文件
 * 3. config.json 文件
 * 4. 默认配置（最低优先级）
 * <p>
 * 支持的环境变量：
 * - TINYCLAW_AGENT_WORKSPACE：工作空间路径
 * - TINYCLAW_AGENT_MODEL：模型名称
 * - TINYCLAW_AGENT_MAX_TOKENS：最大 Token 数
 * - TINYCLAW_AGENT_TEMPERATURE：温度参数
 * - TINYCLAW_CHANNELS_*：通道配置
 * - TINYCLAW_PROVIDERS_*_API_KEY：Provider API 密钥
 * - TINYCLAW_TOOLS_*：工具配置
 * <p>
 * 使用示例：
 * - 加载默认配置：Config config = ConfigLoader.load();
 * - 加载指定配置：Config config = ConfigLoader.load("/path/to/config.json");
 * - 保存配置：ConfigLoader.save("/path/to/config.json", config);
 */
@Slf4j
public class ConfigLoader {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String CONFIG_DIR = ".jclaw";      // 配置目录名
    private static final String CONFIG_FILE = "config.json";   // 配置文件名
    private static final String HOME_PREFIX = "~";             // 用户主目录前缀
    private static final char PATH_SEPARATOR = '/';            // 路径分隔符

    private static Dotenv dotenv = null;  // .env 文件加载器

    /**
     * 从默认路径加载配置。
     * <p>
     * 默认路径为 ~/.tinyclaw/config.json
     *
     * @return 配置对象
     * @throws IOException 读取配置文件失败
     */
    public static Config load() throws IOException {
        return load(getConfigPath());
    }

    /**
     * 从指定路径加载配置。
     * <p>
     * 加载流程：
     * 1. 如果配置文件存在，从文件加载
     * 2. 如果配置文件不存在，使用默认配置
     * 3. 应用环境变量覆盖
     *
     * @param path 配置文件路径
     * @return 配置对象
     * @throws IOException 读取配置文件失败
     */
    public static Config load(String path) throws IOException {
        log.info("加载配置文件: {}", path);
        Config config = loadFromFile(path);
        log.info("加载配置文件完成: {}", toJson(config));
        return config;
    }

    /**
     * 将配置对象转为 JSON 字符串用于日志输出，序列化失败时降级。
     */
    private static String toJson(Config config) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (JsonProcessingException e) {
            return String.valueOf(config);
        }
    }

    /**
     * 从文件加载配置。
     *
     * @param path 配置文件路径
     * @return 配置对象
     * @throws IOException 读取配置文件失败
     */
    private static Config loadFromFile(String path) throws IOException {
        File configFile = new File(path);
        if (!configFile.exists()) {
            return Config.defaultConfig();
        }

        String content = Files.readString(configFile.toPath());
        return objectMapper.readValue(content, Config.class);
    }

    /**
     * 获取默认配置路径。
     *
     * @return 默认配置路径 ~/.tinyclaw/config.json
     */
    public static String getConfigPath() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE).toString();
    }

    /**
     * 保存配置到指定路径。
     * <p>
     * 如果父目录不存在，会自动创建。
     *
     * @param path   配置文件路径
     * @param config 配置对象
     * @throws IOException 写入配置文件失败
     */
    public static void save(String path, Config config) throws IOException {
        File configFile = new File(path);
        ensureParentDirectory(configFile);

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        Files.writeString(configFile.toPath(), json);
    }

    /**
     * 确保父目录存在。
     *
     * @param file 文件对象
     */
    private static void ensureParentDirectory(File file) {
        File parentDir = file.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }
    }
}
