package ca.uhn.fhir.jpa.starter.web;

import static org.hamcrest.Matchers.containsString;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProfileValidationPageControllerTest {

	@Test
	void servesValidationPage() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProfileValidationPageController()).build();

		mockMvc.perform(get("/validate"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
				.andExpect(content().string(containsString("Validate Resource")))
				.andExpect(content().string(containsString("Curizent FHIR Profile Validator")))
				.andExpect(content().string(containsString("$validate")));
	}

	@Test
	void servesLogo() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProfileValidationPageController()).build();

		mockMvc.perform(get("/validate/curizent-logo-white.png"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG));
	}
}
