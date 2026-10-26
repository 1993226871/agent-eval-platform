package io.agenteval.evaluation;
import io.agenteval.catalog.CatalogService;
import io.agenteval.model.ModelProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
@Configuration
public class JudgeTemplateConfiguration {
    @Bean JudgeTemplateService judgeTemplateService(CatalogService catalog,ModelProfileRepository models,ObjectMapper json) throws java.io.IOException {
        var templates=new ArrayList<JudgeTemplateService.Template>();
        for(String name:List.of("content-coverage","evidence-support","tool-result-use")) {
            try(var stream=new ClassPathResource("templates/judges/"+name+".json").getInputStream()) { templates.add(json.readValue(stream,JudgeTemplateService.Template.class)); }
        }
        return new JudgeTemplateService(catalog,models,templates);
    }
}
