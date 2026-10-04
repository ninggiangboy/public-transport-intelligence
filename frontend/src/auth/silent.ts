import { UserManager } from 'oidc-client-ts';

// Runs inside the iframe of signinSilent on /auth/silent.html: posts the Keycloak response (code and state, or an error
// such as login_required) to the app in the parent window, which exchanges the code. The callback reads none of the
// client settings, so they stay empty here and this page needs no env.js.
void new UserManager({ authority: '', client_id: '', redirect_uri: '' }).signinSilentCallback();
