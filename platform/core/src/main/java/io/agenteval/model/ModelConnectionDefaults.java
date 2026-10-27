package io.agenteval.model;

import java.net.URI;
import java.util.Set;

/** Versioned platform tariff: CNY micro-units at the published peak/cache-miss rate.
 * Discounts are not claimed as an exact supplier invoice. Existing versions never change.
 */
public final class ModelConnectionDefaults {
    private ModelConnectionDefaults() {}
    public static ModelProfileCommand resolve(ModelConnection input) {
        if(input==null || input.model()==null || input.endpoint()==null) throw new IllegalArgumentException("Model and API URL are required");
        String model=input.model().strip();
        if(!Set.of("deepseek-flash","deepseek-v4-pro").contains(model))
            throw new IllegalArgumentException("This model has no verified platform preset");
        URI uri=URI.create(input.endpoint().strip());
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Use an HTTPS API URL without credentials or query");
        String path=uri.getPath().replaceAll("/+$","");
        if(path.isEmpty() || path.equals("/v1")) path+="/chat/completions";
        if(!Set.of("/chat/completions","/v1/chat/completions").contains(path)) throw new IllegalArgumentException("Unsupported Chat Completions URL");
        String endpoint=uri.getScheme()+"://"+uri.getRawAuthority()+path;
        boolean flash=model.equals("deepseek-flash");
        return new ModelProfileCommand(model,endpoint,input.apiKey(),model,1_048_576,8192,65536,2,
            "DeepSeek text context 1M; non-thinking default output 8K. Platform tariff 2026-10-27: peak/cache-miss CNY per 1M tokens. https://api-docs.deepseek.com/zh-cn/quick_start/pricing/",
            flash?2:9,flash?8:27,1);
    }
    public static boolean officialEndpoint(String endpoint) {
        return Set.of("https://api.deepseek.com/chat/completions","https://api.deepseek.com/v1/chat/completions").contains(endpoint);
    }
}
