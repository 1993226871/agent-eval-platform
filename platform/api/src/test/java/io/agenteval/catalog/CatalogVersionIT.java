package io.agenteval.catalog;
import io.agenteval.api.PlatformApiApplication;
import io.agenteval.identity.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=PlatformApiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogVersionIT {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",postgres::getJdbcUrl); props.add("spring.datasource.username",postgres::getUsername); props.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired CatalogService catalog;
    @Autowired DatasetService datasets;
    @Autowired TemplateService templates;
    @Autowired IdentityService identities;
    @Autowired ObjectMapper json;

    @Test void preservesPublishedVersionsAndRejectsCrossScopeReferences() throws Exception {
        var a=actor(); var b=actor();
        var draft=catalog.create(a,ResourceKind.DATASET,"退款案例",json.readTree("{\"cases\":[{\"instruction\":\"退款\",\"input\":{\"amount\":100},\"expected\":{\"amount\":100}}]}"));
        var v1=catalog.publish(a,draft.id());
        var edited=json.readTree("{\"cases\":[{\"instruction\":\"退款\",\"input\":{\"amount\":80},\"expected\":{\"amount\":80}}]}");
        catalog.update(a,draft.id(),draft.revision(),"退款案例新版",edited);
        var v2=catalog.publish(a,draft.id());
        assertThat(v2.id()).isNotEqualTo(v1.id());
        assertThat(catalog.version(a,v1.id()).content().at("/cases/0/input/amount").asInt()).isEqualTo(100);
        assertThat(catalog.version(a,v1.id()).contentHash()).isEqualTo(v1.contentHash());
        assertThat(catalog.publish(a,draft.id()).id()).isEqualTo(v2.id());
        assertThatThrownBy(() -> catalog.version(b,v1.id())).isInstanceOf(ScopeViolationException.class);
        var evaluator=catalog.create(a,ResourceKind.EVALUATOR,"金额",json.readTree(rule()));
        var ev=catalog.publish(a,evaluator.id());
        var foreignSuite=catalog.create(b,ResourceKind.SUITE,"非法引用",json.readTree(suite(ev.id(),true)));
        assertThatThrownBy(() -> catalog.publish(b,foreignSuite.id())).isInstanceOf(ScopeViolationException.class);
        var ownSuite=catalog.create(a,ResourceKind.SUITE,"退款评分",json.readTree(suite(ev.id(),true)));
        assertThat(catalog.publish(a,ownSuite.id()).number()).isEqualTo(1);
        catalog.archive(a,evaluator.id());
        assertThat(catalog.version(a,ev.id()).content().get("criterion_id").asText()).isEqualTo("amount");
        assertThatThrownBy(() -> catalog.publish(a,evaluator.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void importIsAtomicAndReportsInvalidJsonlLine() throws Exception {
        var actor=actor();
        var draft=catalog.create(actor,ResourceKind.DATASET,"导入",json.readTree("{\"cases\":[]}"));
        String jsonl="{\"instruction\":\"合法\",\"input\":{}}\n{invalid}";
        assertThatThrownBy(() -> datasets.importJsonl(actor,draft.id(),draft.revision(),jsonl)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 2");
        assertThat(catalog.draft(actor,draft.id()).content().get("cases").size()).isZero();
        var updated=datasets.importJsonl(actor,draft.id(),draft.revision(),"{\"instruction\":\"合法\",\"input\":{}}\n");
        assertThat(updated.content().get("cases").size()).isEqualTo(1);
        assertThatThrownBy(() -> catalog.update(actor,draft.id(),draft.revision(),"覆盖",updated.content())).isInstanceOf(CatalogConflictException.class);
    }

    @Test void rejectsTrailingJsonlTokensWithoutPartialImport() throws Exception {
        var actor=actor();
        var draft=catalog.create(actor,ResourceKind.DATASET,"尾随数据",json.readTree("{\"cases\":[]}"));
        assertThatThrownBy(() -> datasets.importJsonl(actor,draft.id(),draft.revision(),"{\"instruction\":\"测试\",\"input\":{}} {}"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 1");
        assertThat(catalog.draft(actor,draft.id()).content().get("cases").size()).isZero();
    }

    @Test void validatesSuiteRequirementsAndCopiesTemplatesWithoutSharingDrafts() throws Exception {
        var a=actor(); var b=actor();
        var template=templates.list().getFirst();
        var left=templates.copy(a,template.id()); var right=templates.copy(b,template.id());
        assertThat(left.id()).isNotEqualTo(right.id());
        catalog.update(a,left.id(),left.revision(),"私有修改",json.readTree("{\"cases\":[]}"));
        assertThat(catalog.draft(b,right.id()).content().get("cases").size()).isGreaterThan(0);
        var unpublished=catalog.create(a,ResourceKind.EVALUATOR,"草稿",json.readTree(rule()));
        var badSuite=catalog.create(a,ResourceKind.SUITE,"草稿引用",json.readTree(suite(unpublished.id(),true)));
        assertThatThrownBy(() -> catalog.publish(a,badSuite.id())).isInstanceOf(ScopeViolationException.class);
        var ev=catalog.publish(a,unpublished.id());
        var noRequired=catalog.create(a,ResourceKind.SUITE,"无硬条件",json.readTree(suite(ev.id(),false)));
        assertThatThrownBy(() -> catalog.publish(a,noRequired.id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.draft(b,left.id())).isInstanceOf(ScopeViolationException.class);
    }

    @Test void starterBundleBindsOnlyItsPrivatePublishedEvaluator() {
        var actor=actor();
        var bundle=templates.copyStarter(actor);
        var suite=catalog.version(actor,bundle.suiteVersion().id());
        assertThat(suite.content().at("/entries/0/evaluator_version").asText()).isEqualTo(bundle.evaluatorVersion().id().toString());
        assertThat(suite.content().at("/entries/0/required_to_pass").asBoolean()).isTrue();
        assertThat(catalog.version(actor,bundle.datasetVersion().id()).content().get("cases").size()).isEqualTo(1);
        assertThatThrownBy(() -> catalog.version(actor(),bundle.suiteVersion().id())).isInstanceOf(ScopeViolationException.class);
    }
    private WorkspaceActor actor() { return identities.createUser(UUID.randomUUID()+"@test.local","Temporary-Password-1",false).actor(); }
    private String rule() { return "{\"type\":\"RULE\",\"criterion_id\":\"amount\",\"required_evidence\":[\"output\"],\"threshold\":1,\"config\":{\"operator\":\"eq\",\"selector\":\"/output/amount\",\"expected\":100}}"; }
    private String suite(UUID version,boolean required) { return "{\"entries\":[{\"evaluator_version\":\""+version+"\",\"dimension\":\"outcome\",\"required_to_pass\":"+required+",\"weight\":1}]}"; }
}
