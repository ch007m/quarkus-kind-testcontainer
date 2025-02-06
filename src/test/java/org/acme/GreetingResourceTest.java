package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class GreetingResourceTest {
    public long timeOut;
    @Test
    public void testGreeting() {
        if (System.getenv("argocd.resource.timeout") != null) {
            timeOut = Long.parseLong(System.getenv("argocd.resource.timeout"));
            System.out.println("Timeout: " + timeOut);
        }
    }
    @Test
    void testHelloEndpoint() {
        given()
          .when().get("/hello")
          .then()
             .statusCode(200)
             .body(is("Hello from Quarkus REST"));
    }

}