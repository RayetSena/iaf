package nl.nn.adapterframework.management.bus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import nl.nn.adapterframework.testutil.SpringRootInitializer;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(initializers = {SpringRootInitializer.class})
public class TestBusAuthorisation extends BusTestBase {

	@Test
	@WithMockUser(authorities = { "ROLE_IbisTester" })
	public void callTestEndpointAuthorisedIsAdmin() {
		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.DEBUG, BusAction.MANAGE);
		boolean isAdmin = Boolean.parseBoolean((String)callSyncGateway(request).getPayload());
		assertTrue(isAdmin);
	}

	@Test
	@WithMockUser(authorities = { "ROLE_IbisAdmin" })
	public void callTestEndpointAuthorisedNotAdmin() {
		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.DEBUG, BusAction.MANAGE);
		boolean isAdmin = Boolean.parseBoolean((String)callSyncGateway(request).getPayload());
		assertFalse(isAdmin);
	}

	@Test
	@WithMockUser(authorities = { "ROLE_IbisObserver" })
	public void callTestEndpointUnAuthorised() {
		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.DEBUG, BusAction.MANAGE);
		try {
			callSyncGateway(request);
		} catch (Exception e) {
			assertTrue(e.getCause() instanceof AccessDeniedException);
		}
	}

	@Test
	public void callTestEndpointNoUser() {
		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.DEBUG, BusAction.MANAGE);
		try {
			callSyncGateway(request);
		} catch (Exception e) {
			assertTrue(e.getCause() instanceof AuthenticationCredentialsNotFoundException);
		}
	}
}