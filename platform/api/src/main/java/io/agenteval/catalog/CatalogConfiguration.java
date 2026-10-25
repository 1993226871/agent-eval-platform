package io.agenteval.catalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import java.util.List;

@Configuration
public class CatalogConfiguration {
    @Bean CatalogService catalogService(CatalogRepository repository) { return new CatalogService(repository); }
    @Bean DatasetService datasetService(CatalogService catalog,ObjectMapper json) { return new DatasetService(catalog,json); }
    @Bean EvaluatorCatalogService evaluatorCatalogService(CatalogService catalog) { return new EvaluatorCatalogService(catalog); }
    @Bean SuiteService suiteService(CatalogService catalog) { return new SuiteService(catalog); }
    @Bean TemplateService templateService(CatalogService catalog,ObjectMapper json) throws java.io.IOException {
        try(var stream=new ClassPathResource("templates/starter.json").getInputStream()) {
            List<TemplateService.Template> templates=json.readValue(stream,json.getTypeFactory().constructCollectionType(List.class,TemplateService.Template.class));
            return new TemplateService(catalog,templates);
        }
    }
}
