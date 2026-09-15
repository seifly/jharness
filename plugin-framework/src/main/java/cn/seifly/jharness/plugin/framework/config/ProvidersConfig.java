package cn.seifly.jharness.plugin.framework.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * LLM 提供商配置类（SDK 共享）
 * 支持多个 LLM 提供商：OpenRouter、Anthropic、OpenAI、Gemini、智谱、DashScope、Ollama
 *
 * <p>此类作为跨插件共享数据结构提升至 plugin-framework SDK，
 * 避免 PF4J 独立类加载器导致 workflow-plugin 在运行期抛出
 * {@code NoClassDefFoundError}。
 */
@Data
public class ProvidersConfig {

    private ProviderConfig openrouter;
    private ProviderConfig anthropic;
    private ProviderConfig openai;
    private ProviderConfig zhipu;
    private ProviderConfig gemini;
    private ProviderConfig dashscope;
    private ProviderConfig ollama;
    private ProviderConfig moonshot;
    public ProvidersConfig() {
        this.openrouter = new ProviderConfig(getDefaultApiBase("openrouter"));
        this.anthropic = new ProviderConfig(getDefaultApiBase("anthropic"));
        this.openai = new ProviderConfig(getDefaultApiBase("openai"));
        this.zhipu = new ProviderConfig(getDefaultApiBase("zhipu"));
        this.gemini = new ProviderConfig(getDefaultApiBase("gemini"));
        this.dashscope = new ProviderConfig(getDefaultApiBase("dashscope"));
        this.ollama = new ProviderConfig(getDefaultApiBase("ollama"));
        this.moonshot = new ProviderConfig(getDefaultApiBase("moonshot"));
    }


    @JsonIgnore
    public List<ProviderConfig> getAllProviders() {
        return Arrays.asList(
                openrouter, anthropic, openai, gemini,
                zhipu, dashscope, ollama
        );
    }

    @JsonIgnore
    public Optional<ProviderConfig> getFirstValidProvider() {
        return getAllProviders().stream()
                .filter(p -> p != null && p.isValid())
                .findFirst();
    }

    /**
     * 获取第一个可用的 Provider（优先 ollama，其次其他有效 provider）。
     */
    @JsonIgnore
    public Optional<ProviderWithName> getFirstAvailableProvider() {
        if (ollama != null && ollama.isValidForLocal()) {
            return Optional.of(new ProviderWithName("ollama", ollama));
        }
        List<ProviderWithName> providers = Arrays.asList(
                new ProviderWithName("openrouter", openrouter),
                new ProviderWithName("openai", openai),
                new ProviderWithName("anthropic", anthropic),
                new ProviderWithName("zhipu", zhipu),
                new ProviderWithName("dashscope", dashscope),
                new ProviderWithName("moonshot", moonshot),
                new ProviderWithName("gemini", gemini)
        );
        return providers.stream()
                .filter(p -> p.config != null && p.config.isValid())
                .findFirst();
    }

    public static class ProviderWithName {
        public final String name;
        public final ProviderConfig config;
        public ProviderWithName(String name, ProviderConfig config) {
            this.name = name;
            this.config = config;
        }
    }

    @JsonIgnore
    public ProviderConfig getByName(String providerName) {
        if (providerName == null) return null;
        return switch (providerName) {
            case "openrouter" -> openrouter;
            case "openai"     -> openai;
            case "anthropic"  -> anthropic;
            case "zhipu"      -> zhipu;
            case "dashscope"  -> dashscope;
            case "gemini"     -> gemini;
            case "ollama"     -> ollama;
            case "moonshot"   -> moonshot;
            default           -> null;
        };
    }

    public String getProviderName(ProviderConfig provider) {
        if (provider == openrouter) return "openrouter";
        if (provider == anthropic) return "anthropic";
        if (provider == openai) return "openai";
        if (provider == gemini) return "gemini";
        if (provider == zhipu) return "zhipu";
        if (provider == dashscope) return "dashscope";
        if (provider == ollama) return "ollama";
        if (provider == moonshot) return "moonshot";
        return "unknown";
    }

    public static String getDefaultApiBase(String providerName) {
        switch (providerName) {
            case "openrouter":  return "https://openrouter.ai/api/v1";
            case "anthropic":   return "https://api.anthropic.com/v1";
            case "openai":      return "https://api.openai.com/v1";
            case "gemini":      return "https://generativelanguage.googleapis.com/v1beta";
            case "zhipu":       return "https://open.bigmodel.cn/api/paas/v4";
            case "dashscope":   return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "ollama":      return "http://localhost:11434/v1";
            case "moonshot":      return "https://api.moonshot.cn/v1";
            default:            return "https://openrouter.ai/api/v1";
        }
    }

    @Getter
    @Setter
    public static class ProviderConfig {
        private String apiKey;
        private String apiBase;

        public ProviderConfig() {
            this.apiKey = "";
            this.apiBase = "";
        }

        public ProviderConfig(String defaultApiBase) {
            this.apiKey = "";
            this.apiBase = defaultApiBase;
        }

        @JsonIgnore
        public boolean isValid() {
            return apiKey != null && !apiKey.isEmpty();
        }

        @JsonIgnore
        public boolean isValidForLocal() {
            return hasApiBase();
        }

        @JsonIgnore
        public boolean hasApiBase() {
            return apiBase != null && !apiBase.isEmpty();
        }

        public String getApiBaseOrDefault(String defaultBase) {
            return (apiBase != null && !apiBase.isEmpty()) ? apiBase : defaultBase;
        }
    }
}
