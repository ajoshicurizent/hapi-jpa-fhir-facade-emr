package ca.uhn.fhir.jpa.starter.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves a course UI for validating a resource instance against one profile
 * via the FHIR {@code $validate} operation.
 */
@Controller
public class ProfileValidationPageController {

	static final ClassPathResource LOGO = new ClassPathResource("static/validate/curizent-logo-white.png");
	static final ClassPathResource PAGE = new ClassPathResource("static/validate/index.html");

	@GetMapping(path = {"/validate", "/validate/"}, produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<ClassPathResource> validationPage() {
		if (!PAGE.exists()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(PAGE);
	}

	@GetMapping(path = "/validate/curizent-logo-white.png", produces = MediaType.IMAGE_PNG_VALUE)
	public ResponseEntity<ClassPathResource> logo() {
		if (!LOGO.exists()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(LOGO);
	}
}
