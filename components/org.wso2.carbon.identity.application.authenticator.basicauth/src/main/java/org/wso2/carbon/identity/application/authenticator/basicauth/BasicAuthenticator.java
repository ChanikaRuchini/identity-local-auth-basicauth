/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.application.authenticator.basicauth;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.core.util.SignatureUtil;
import org.wso2.carbon.identity.application.authentication.framework.AbstractApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.AuthenticatorFlowStatus;
import org.wso2.carbon.identity.application.authentication.framework.LocalApplicationAuthenticator;
import org.wso2.carbon.identity.application.authentication.framework.config.ConfigurationFacade;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.AuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.exception.InvalidCredentialsException;
import org.wso2.carbon.identity.application.authentication.framework.exception.LogoutFailedException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkConstants;
import org.wso2.carbon.identity.application.authentication.framework.util.FrameworkUtils;
import org.wso2.carbon.identity.application.authenticator.basicauth.internal.BasicAuthenticatorDataHolder;
import org.wso2.carbon.identity.application.authenticator.basicauth.internal.BasicAuthenticatorServiceComponent;
import org.wso2.carbon.identity.application.authenticator.basicauth.util.AutoLoginConstant;
import org.wso2.carbon.identity.application.authenticator.basicauth.util.BasicAuthErrorConstants.ErrorMessages;
import org.wso2.carbon.identity.application.common.model.Property;
import org.wso2.carbon.identity.application.common.model.User;
import org.wso2.carbon.identity.captcha.connector.recaptcha.SSOLoginReCaptchaConfig;
import org.wso2.carbon.identity.captcha.util.CaptchaConstants;
import org.wso2.carbon.identity.core.model.IdentityErrorMsgContext;
import org.wso2.carbon.identity.core.util.IdentityCoreConstants;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.governance.IdentityGovernanceException;
import org.wso2.carbon.identity.multi.attribute.login.mgt.ResolvedUserResult;
import org.wso2.carbon.identity.recovery.RecoveryScenarios;
import org.wso2.carbon.identity.recovery.util.Utils;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserStoreClientException;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.common.AuthenticationResult;
import org.wso2.carbon.user.core.constants.UserCoreClaimConstants;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Username Password based Authenticator.
 */
public class BasicAuthenticator extends AbstractApplicationAuthenticator
        implements LocalApplicationAuthenticator {

    private static final long serialVersionUID = 1819664539416029785L;
    private static final String PASSWORD_PROPERTY = "PASSWORD_PROPERTY";
    private static final String PASSWORD_RESET_ENDPOINT = "accountrecoveryendpoint/confirmrecovery.do?";
    private static final Log log = LogFactory.getLog(BasicAuthenticator.class);
    private static final String RESEND_CONFIRMATION_RECAPTCHA_ENABLE = "SelfRegistration.ResendConfirmationReCaptcha";
    private static final String AUTO_LOGIN_FLOW_HANDLED = "autoLoginHandled";
    private static final String APPEND_USER_TENANT_TO_USERNAME = "appendUserTenantToUsername";
    private static final String RE_CAPTCHA_USER_DOMAIN = "user-domain-recaptcha";
    private List<String> omittingErrorParams = null;

    /**
     * USER_EXIST_THREAD_LOCAL_PROPERTY is used to maintain the state of user existence
     * which has used in org.wso2.carbon.identity.governance.listener.IdentityMgtEventListener.
     */
    private static final String USER_EXIST_THREAD_LOCAL_PROPERTY = "userExistThreadLocalProperty";

    @Override
    public boolean canHandle(HttpServletRequest request) {
        String userName = request.getParameter(BasicAuthenticatorConstants.USER_NAME);
        String password = request.getParameter(BasicAuthenticatorConstants.PASSWORD);
        Cookie autoLoginCookie = getAutoLoginCookie(request.getCookies());

        return (userName != null && password != null) || autoLoginCookie != null;
    }

    @Override
    public AuthenticatorFlowStatus process(HttpServletRequest request,
                                           HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException, LogoutFailedException {

        Cookie autoLoginCookie = getAutoLoginCookie(request.getCookies());

        if (context.isLogoutRequest()) {
            return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
        } else if (autoLoginCookie != null && !Boolean.TRUE.equals(context.getProperty(AUTO_LOGIN_FLOW_HANDLED)) &&
                isEnableAutoLoginEnabled(context, autoLoginCookie)) {
            try {
                context.setProperty(AUTO_LOGIN_FLOW_HANDLED, true);
                return executeAutoLoginFlow(request, response, context, autoLoginCookie);
            } catch (AuthenticationFailedException e) {
                request.setAttribute(FrameworkConstants.REQ_ATTR_HANDLED, true);
                // Decide whether we need to redirect to the login page to retry authentication.
                boolean sendToMultiOptionPage =
                        isStepHasMultiOption(context) && isRedirectToMultiOptionPageOnFailure();
                if (retryAuthenticationEnabled(context) && !sendToMultiOptionPage) {
                    // The Authenticator will re-initiate the authentication and retry.
                    context.setCurrentAuthenticator(getName());
                    initiateAuthenticationRequest(request, response, context);
                    return AuthenticatorFlowStatus.INCOMPLETE;
                } else {
                    context.setProperty(FrameworkConstants.LAST_FAILED_AUTHENTICATOR, getName());
                    // By throwing this exception step handler will redirect to multi options page if
                    // multi-option are available in the step.
                    if (log.isDebugEnabled()) {
                        log.debug("Error occurred while executing the Auto Login from Cookie flow: " + e);
                    }
                    throw e;
                }
            } finally {
                removeAutoLoginCookie(response, autoLoginCookie);
            }
        }
        return super.process(request, response, context);
    }

    protected AuthenticatorFlowStatus executeAutoLoginFlow(HttpServletRequest request, HttpServletResponse response,
                                                         AuthenticationContext context, Cookie autoLoginCookie)
            throws AuthenticationFailedException {

        String decodedValue = new String(Base64.getDecoder().decode(autoLoginCookie.getValue()));
        JSONObject cookieValueJSON = transformToJSON(decodedValue);
        String signature = (String) cookieValueJSON.get(AutoLoginConstant.SIGNATURE);
        String content = (String) cookieValueJSON.get(AutoLoginConstant.CONTENT);

        JSONObject contentJSON = transformToJSON(content);
        String usernameInCookie = (String) contentJSON.get(AutoLoginConstant.USERNAME);

        if (contentJSON.get(AutoLoginConstant.CREATED_TIME) == null) {
            throw new AuthenticationFailedException("The created time is not available in the ALOR cookie content.");
        }
        long createdTime = (long) contentJSON.get(AutoLoginConstant.CREATED_TIME);
        validateCreatedTime(createdTime);

        String alias = null;
        String flowType = resolveAutoLoginFlow(autoLoginCookie.getValue());
        if (AutoLoginConstant.SIGNUP.equals(flowType)) {
            alias = getSelfRegistrationAutoLoginAlias(context);
        }

        if (log.isDebugEnabled()) {
            log.debug("Started executing Auto Login from Cookie flow.");
        }

        validateCookieSignature(content, signature, alias);

        String userStoreDomain = UserCoreUtil.extractDomainFromName(usernameInCookie);
        // Set the user store domain in thread local as downstream code depends on it. This will be cleared at the
        // end of the request at the framework.
        UserCoreUtil.setDomainInThreadLocal(userStoreDomain);

        usernameInCookie = FrameworkUtils.prependUserStoreDomainToName(usernameInCookie);

        context.setSubject(AuthenticatedUser.createLocalAuthenticatedUserFromSubjectIdentifier(usernameInCookie));
        return AuthenticatorFlowStatus.SUCCESS_COMPLETED;
    }

    @Override
    protected void initiateAuthenticationRequest(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {

        Map<String, String> parameterMap = getAuthenticatorConfig().getParameterMap();
        String showAuthFailureReason = null;
        String maskUserNotExistsErrorCode = null;
        String maskAdminForcedPasswordResetErrorCode = null;
        if (parameterMap != null) {
            showAuthFailureReason = parameterMap.get(BasicAuthenticatorConstants.CONF_SHOW_AUTH_FAILURE_REASON);
            if (log.isDebugEnabled()) {
                log.debug(BasicAuthenticatorConstants.CONF_SHOW_AUTH_FAILURE_REASON + " has been set as : " +
                        showAuthFailureReason);
            }
            if (Boolean.parseBoolean(showAuthFailureReason)) {
                maskUserNotExistsErrorCode =
                        parameterMap.get(BasicAuthenticatorConstants.CONF_MASK_USER_NOT_EXISTS_ERROR_CODE);
                if (log.isDebugEnabled()) {
                    log.debug(BasicAuthenticatorConstants.CONF_MASK_USER_NOT_EXISTS_ERROR_CODE +
                            " has been set as : " + maskUserNotExistsErrorCode);
                }

                String errorParamsToOmit = parameterMap.get(BasicAuthenticatorConstants.CONF_ERROR_PARAMS_TO_OMIT);
                if (log.isDebugEnabled()) {
                    log.debug(BasicAuthenticatorConstants.CONF_ERROR_PARAMS_TO_OMIT + " has been set as : " +
                            errorParamsToOmit);
                }
                if (StringUtils.isNotBlank(errorParamsToOmit)) {
                    errorParamsToOmit = errorParamsToOmit.replaceAll(" ", "");
                    omittingErrorParams = new ArrayList<>(Arrays.asList(errorParamsToOmit.split(",")));
                }
            }
            maskAdminForcedPasswordResetErrorCode =
                    parameterMap.get(BasicAuthenticatorConstants.CONF_MASK_ADMIN_FORCED_PASSWORD_RESET_ERROR_CODE);
            if (log.isDebugEnabled()) {
                log.debug(BasicAuthenticatorConstants.CONF_MASK_ADMIN_FORCED_PASSWORD_RESET_ERROR_CODE +
                        " has been set as : " + maskAdminForcedPasswordResetErrorCode);
            }
        }

        String loginPage = ConfigurationFacade.getInstance().getAuthenticationEndpointURL();
        String retryPage = ConfigurationFacade.getInstance().getAuthenticationEndpointRetryURL();
        String queryParams = context.getContextIdIncludedQueryParams();
        String password = (String) context.getProperty(PASSWORD_PROPERTY);
        String redirectURL;
        context.getProperties().remove(PASSWORD_PROPERTY);

        Map<String, String> runtimeParams = getRuntimeParams(context);
        if (runtimeParams != null) {
            String inputType = null;
            String usernameFromContext = runtimeParams.get(FrameworkConstants.JSAttributes.JS_OPTIONS_USERNAME);
            if (usernameFromContext != null) {
                inputType = FrameworkConstants.INPUT_TYPE_IDENTIFIER_FIRST;
            }
            if (FrameworkConstants.INPUT_TYPE_IDENTIFIER_FIRST.equalsIgnoreCase(inputType)) {
                queryParams += "&" + FrameworkConstants.RequestParams.INPUT_TYPE + "=" + inputType;
                context.addEndpointParam(FrameworkConstants.JSAttributes.JS_OPTIONS_USERNAME, usernameFromContext);
            }
        }

        try {
            String retryParam = "";

            if (context.isRetrying()) {
                if (context.getProperty(FrameworkConstants.CONTEXT_PROP_INVALID_EMAIL_USERNAME) != null &&
                        (Boolean) context.getProperty(FrameworkConstants.CONTEXT_PROP_INVALID_EMAIL_USERNAME)) {
                    retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                            BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "emailusername.fail.message";
                    context.setProperty(FrameworkConstants.CONTEXT_PROP_INVALID_EMAIL_USERNAME, false);
                } else {
                    retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                            BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "login.fail.message";
                }
            }

            if (context.getProperty("UserTenantDomainMismatch") != null &&
                    (Boolean) context.getProperty("UserTenantDomainMismatch")) {
                retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                        BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "user.tenant.domain.mismatch.message";
                context.setProperty("UserTenantDomainMismatch", false);
            }

            IdentityErrorMsgContext errorContext = IdentityUtil.getIdentityErrorMsg();
            IdentityUtil.clearIdentityErrorMsg();

            if (errorContext != null && errorContext.getErrorCode() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Identity error message context is not null");
                }
                String errorCode = errorContext.getErrorCode();

                if (errorCode.equals(IdentityCoreConstants.USER_ACCOUNT_NOT_CONFIRMED_ERROR_CODE)) {
                    retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                            BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "account.confirmation.pending";
                    String username = request.getParameter(BasicAuthenticatorConstants.USER_NAME);
                    Object domain = IdentityUtil.threadLocalProperties.get().get(RE_CAPTCHA_USER_DOMAIN);
                    if (domain != null) {
                        username = IdentityUtil.addDomainToName(username, domain.toString());
                    }

                    redirectURL = loginPage + ("?" + queryParams) + BasicAuthenticatorConstants.FAILED_USERNAME
                            + URLEncoder.encode(username, BasicAuthenticatorConstants.UTF_8) +
                            BasicAuthenticatorConstants.ERROR_CODE + errorCode + BasicAuthenticatorConstants
                            .AUTHENTICATORS + getName() + ":" + BasicAuthenticatorConstants.LOCAL + retryParam;

                } else if (errorCode.equals(
                        IdentityCoreConstants.ADMIN_FORCED_USER_PASSWORD_RESET_VIA_EMAIL_LINK_ERROR_CODE)) {
                    retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                            BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + "password.reset.pending";
                    if (Boolean.parseBoolean(maskAdminForcedPasswordResetErrorCode)) {

                        errorCode = UserCoreConstants.ErrorCode.INVALID_CREDENTIAL;
                        if (log.isDebugEnabled()) {
                            log.debug("Masking password reset pending error code: " +
                                    IdentityCoreConstants.ADMIN_FORCED_USER_PASSWORD_RESET_VIA_EMAIL_LINK_ERROR_CODE +
                                    " with error code: " + errorCode);
                        }
                        retryParam = "&authFailure=true&authFailureMsg=login.fail.message";
                    }
                    redirectURL = loginPage + ("?" + queryParams) +
                            BasicAuthenticatorConstants.FAILED_USERNAME + URLEncoder.encode(request.getParameter(
                            BasicAuthenticatorConstants.USER_NAME), BasicAuthenticatorConstants.UTF_8) +
                            BasicAuthenticatorConstants.ERROR_CODE + errorCode +
                            BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                            BasicAuthenticatorConstants.LOCAL + retryParam;

                } else if (errorCode.equals(
                        IdentityCoreConstants.ADMIN_FORCED_USER_PASSWORD_RESET_VIA_OTP_ERROR_CODE)) {
                    String username = request.getParameter(BasicAuthenticatorConstants.USER_NAME);
                    String tenantDomain = getTenantDomainFromUserName(context, username);

                    // Setting callback so that the user is prompted to login after a password reset.
                    String callback = loginPage + ("?" + queryParams)
                            + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                            BasicAuthenticatorConstants.LOCAL;
                    String reason = RecoveryScenarios.ADMIN_FORCED_PASSWORD_RESET_VIA_OTP.name();

                    redirectURL = (PASSWORD_RESET_ENDPOINT + queryParams) +
                            BasicAuthenticatorConstants.USER_NAME_PARAM + URLEncoder.encode(username,
                            BasicAuthenticatorConstants.UTF_8) + BasicAuthenticatorConstants.TENANT_DOMAIN_PARAM +
                            URLEncoder.encode(tenantDomain, BasicAuthenticatorConstants.UTF_8) +
                            BasicAuthenticatorConstants.CONFIRMATION_PARAM + URLEncoder.encode(password,
                            BasicAuthenticatorConstants.UTF_8) + BasicAuthenticatorConstants.CALLBACK_PARAM +
                            URLEncoder.encode(callback, BasicAuthenticatorConstants.UTF_8) +
                            BasicAuthenticatorConstants.REASON_PARAM +
                            URLEncoder.encode(reason, BasicAuthenticatorConstants.UTF_8);
                } else if ("true".equals(showAuthFailureReason)) {

                    if (Boolean.parseBoolean(maskUserNotExistsErrorCode) &&
                            StringUtils.contains(errorCode, UserCoreConstants.ErrorCode.USER_DOES_NOT_EXIST)) {

                        errorCode = UserCoreConstants.ErrorCode.INVALID_CREDENTIAL;

                        if (log.isDebugEnabled()) {
                            log.debug("Masking user not found error code: " +
                                    UserCoreConstants.ErrorCode.USER_DOES_NOT_EXIST + " with error code: " +
                                    errorCode);
                        }
                    }

                    String reason = null;
                    if (errorCode.contains(":")) {
                        String[] errorCodeReason = errorCode.split(":", 2);
                        errorCode = errorCodeReason[0];
                        if (errorCodeReason.length > 1) {
                            reason = errorCodeReason[1];
                        }
                    }
                    int remainingAttempts =
                            errorContext.getMaximumLoginAttempts() - errorContext.getFailedLoginAttempts();

                    if (log.isDebugEnabled()) {
                        log.debug("errorCode : " + errorCode);
                        log.debug("username : " + request.getParameter(BasicAuthenticatorConstants.USER_NAME));
                        log.debug("remainingAttempts : " + remainingAttempts);
                    }

                    if (errorCode.equals(UserCoreConstants.ErrorCode.INVALID_CREDENTIAL)) {
                        Map<String, String> paramMap = new HashMap<>();
                        paramMap.put(BasicAuthenticatorConstants.ERROR_CODE, errorCode);
                        paramMap.put(BasicAuthenticatorConstants.FAILED_USERNAME,
                                URLEncoder.encode(request.getParameter(BasicAuthenticatorConstants.USER_NAME),
                                        BasicAuthenticatorConstants.UTF_8));
                        paramMap.put(BasicAuthenticatorConstants.REMAINING_ATTEMPTS, String.valueOf(remainingAttempts));

                        retryParam = retryParam + buildErrorParamString(paramMap);
                        redirectURL = loginPage + ("?" + queryParams)
                                + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                                BasicAuthenticatorConstants.LOCAL + retryParam;

                    } else if (errorCode.equals(UserCoreConstants.ErrorCode.USER_IS_LOCKED)) {
                        Map<String, String> paramMap = new HashMap<>();
                        paramMap.put(BasicAuthenticatorConstants.ERROR_CODE, errorCode);
                        paramMap.put(BasicAuthenticatorConstants.FAILED_USERNAME,
                                URLEncoder.encode(request.getParameter(BasicAuthenticatorConstants.USER_NAME),
                                        BasicAuthenticatorConstants.UTF_8));

                        if (StringUtils.isNotBlank(reason)) {
                            paramMap.put(BasicAuthenticatorConstants.LOCKED_REASON, reason);
                        }
                        if (remainingAttempts == 0) {
                            paramMap.put(BasicAuthenticatorConstants.REMAINING_ATTEMPTS, "0");
                        }

                        redirectURL = response.encodeRedirectURL(retryPage + ("?" + queryParams))
                                + buildErrorParamString(paramMap);
                    } else if (errorCode.equals(
                            IdentityCoreConstants.ADMIN_FORCED_USER_PASSWORD_RESET_VIA_OTP_MISMATCHED_ERROR_CODE)) {
                        Map<String, String> paramMap = new HashMap<>();
                        paramMap.put(BasicAuthenticatorConstants.ERROR_CODE, errorCode);
                        paramMap.put(BasicAuthenticatorConstants.FAILED_USERNAME,
                                URLEncoder.encode(request.getParameter(BasicAuthenticatorConstants.USER_NAME),
                                        BasicAuthenticatorConstants.UTF_8));

                        retryParam = "&authFailure=true&authFailureMsg=login.fail.message";
                        redirectURL = loginPage + ("?" + queryParams)
                                + buildErrorParamString(paramMap)
                                + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                                BasicAuthenticatorConstants.LOCAL + retryParam;

                    } else {
                        Map<String, String> paramMap = new HashMap<>();
                        paramMap.put(BasicAuthenticatorConstants.ERROR_CODE, errorCode);
                        paramMap.put(BasicAuthenticatorConstants.FAILED_USERNAME,
                                URLEncoder.encode(request.getParameter(BasicAuthenticatorConstants.USER_NAME),
                                        BasicAuthenticatorConstants.UTF_8));
                        if (StringUtils.isNotBlank(reason)) {
                            retryParam = BasicAuthenticatorConstants.AUTH_FAILURE_PARAM + "true" +
                                    BasicAuthenticatorConstants.AUTH_FAILURE_MSG_PARAM + URLEncoder.encode(reason,
                                    BasicAuthenticatorConstants.UTF_8);
                        }
                        retryParam = retryParam + buildErrorParamString(paramMap);
                        redirectURL = loginPage + ("?" + queryParams)
                                + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":"
                                + BasicAuthenticatorConstants.LOCAL + retryParam;
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Unknown identity error code.");
                    }
                    redirectURL = loginPage + ("?" + queryParams)
                            + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                            BasicAuthenticatorConstants.LOCAL + retryParam;

                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("Identity error message context is null");
                }
                redirectURL = loginPage + ("?" + queryParams)
                        + BasicAuthenticatorConstants.AUTHENTICATORS + getName() + ":" +
                        BasicAuthenticatorConstants.LOCAL + retryParam;
            }

            redirectURL += getCaptchaParams(context.getLoginTenantDomain());
            response.sendRedirect(redirectURL);
        } catch (IOException e) {
            throw new AuthenticationFailedException(ErrorMessages.SYSTEM_ERROR_WHILE_AUTHENTICATING.getCode(),
                    e.getMessage(),
                    User.getUserFromUserName(request.getParameter(BasicAuthenticatorConstants.USER_NAME)), e);
        }
    }


    @Override
    protected void processAuthenticationResponse(HttpServletRequest request,
                                                 HttpServletResponse response, AuthenticationContext context)
            throws AuthenticationFailedException {

        String loginIdentifierFromRequest = request.getParameter(BasicAuthenticatorConstants.USER_NAME);
        FrameworkUtils.validateUsername(loginIdentifierFromRequest, context);
        Map<String, String> runtimeParams = getRuntimeParams(context);
        if (runtimeParams != null) {
            // FrameworkUtils.preprocessUsername will not append the tenant domain to username, if you are using
            // email as username and EnableEmailUserName config is not enabled. So for a SaaS app, this config needs
            // to be enabled to add the tenant domain to email username if EnableEmailUserName is not enabled in the
            // system.
            String appendUserTenant = runtimeParams.get(APPEND_USER_TENANT_TO_USERNAME);
            if (Boolean.parseBoolean(appendUserTenant)) {
                loginIdentifierFromRequest = loginIdentifierFromRequest + "@" + context.getUserTenantDomain();
            }
        }
        String username = FrameworkUtils.preprocessUsername(loginIdentifierFromRequest, context);
        String requestTenantDomain = MultitenantUtils.getTenantDomain(username);
        String tenantAwareUsername = MultitenantUtils.getTenantAwareUsername(username);
        String userId = null;
        ResolvedUserResult resolvedUserResult = FrameworkUtils.processMultiAttributeLoginIdentification(
                tenantAwareUsername, requestTenantDomain);
        if (resolvedUserResult != null && ResolvedUserResult.UserResolvedStatus.SUCCESS.
                equals(resolvedUserResult.getResolvedStatus())) {
            tenantAwareUsername = resolvedUserResult.getUser().getUsername();
            username = UserCoreUtil.addTenantDomainToEntry(tenantAwareUsername, requestTenantDomain);
            userId = resolvedUserResult.getUser().getUserID();
        }
        String password = request.getParameter(BasicAuthenticatorConstants.PASSWORD);

        Map<String, Object> authProperties = context.getProperties();
        if (authProperties == null) {
            authProperties = new HashMap<>();
            context.setProperties(authProperties);
        }

        if (runtimeParams != null) {
            String usernameFromContext = runtimeParams.get(FrameworkConstants.JSAttributes.JS_OPTIONS_USERNAME);
            if (usernameFromContext != null &&
                    !usernameFromContext.equals(request.getParameter(BasicAuthenticatorConstants.USER_NAME))) {
                if (log.isDebugEnabled()) {
                    log.debug("Username set for identifier first login: " + usernameFromContext + " and username " +
                            "submitted from login page" + username + " does not match.");
                }
                throw new InvalidCredentialsException(ErrorMessages.CREDENTIAL_MISMATCH.getCode(),
                        ErrorMessages.CREDENTIAL_MISMATCH.getMessage());
            }
        }

        authProperties.put(PASSWORD_PROPERTY, password);

        boolean isAuthenticated = false;
        AbstractUserStoreManager userStoreManager = getUserStoreManager(username, requestTenantDomain);
        // Reset RE_CAPTCHA_USER_DOMAIN thread local variable before the authentication
        IdentityUtil.threadLocalProperties.get().remove(RE_CAPTCHA_USER_DOMAIN);
        // Check the authentication
        AuthenticationResult authenticationResult;
        try {
            setUserExistThreadLocal();

            if (userId != null) {
                authenticationResult = userStoreManager.authenticateWithID(userId, password);
            } else {
                authenticationResult = userStoreManager.authenticateWithID(UserCoreClaimConstants.USERNAME_CLAIM_URI,
                        tenantAwareUsername, password, UserCoreConstants.DEFAULT_PROFILE);
            }
            if (AuthenticationResult.AuthenticationStatus.SUCCESS == authenticationResult.getAuthenticationStatus()
                    && authenticationResult.getAuthenticatedUser().isPresent()) {
                isAuthenticated = true;
            }
            if (isAuthPolicyAccountExistCheck()) {
                checkUserExistence();
            }
        } catch (UserStoreClientException e) {
            if (log.isDebugEnabled()) {
                log.debug("BasicAuthentication failed while trying to authenticate the user " + username, e);
            }
            IdentityErrorMsgContext customErrorMessageContext = new IdentityErrorMsgContext(e.getErrorCode() +
                    ":" + e.getMessage());
            IdentityUtil.setIdentityErrorMsg(customErrorMessageContext);
            throw new AuthenticationFailedException(
                    ErrorMessages.USER_STORE_EXCEPTION_WHILE_TRYING_TO_AUTHENTICATE.getCode(), e.getMessage(),
                    User.getUserFromUserName(username), e);
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            if (log.isDebugEnabled()) {
                log.debug("BasicAuthentication failed while trying to authenticate the user " + username, e);
            }
            // Sometimes client exceptions are wrapped in the super class.
            // Therefore checking for possible client exception.
            Throwable rootCause = ExceptionUtils.getRootCause(e);
            if (rootCause instanceof UserStoreClientException) {
                IdentityErrorMsgContext customErrorMessageContext = new IdentityErrorMsgContext(
                        ((UserStoreClientException) rootCause).getErrorCode() + ":" + rootCause.getMessage());
                IdentityUtil.setIdentityErrorMsg(customErrorMessageContext);
            }
            throw new AuthenticationFailedException(
                    ErrorMessages.USER_STORE_EXCEPTION_WHILE_TRYING_TO_AUTHENTICATE.getCode(), e.getMessage(),
                    User.getUserFromUserName(username), e);
        } finally {
            clearUserExistThreadLocal();
        }

        if (!isAuthenticated) {
            if (log.isDebugEnabled()) {
                log.debug("User authentication failed due to invalid credentials");
            }
            if (IdentityUtil.threadLocalProperties.get().get(RE_CAPTCHA_USER_DOMAIN) != null) {
                username = IdentityUtil.addDomainToName(
                        username, IdentityUtil.threadLocalProperties.get().get(RE_CAPTCHA_USER_DOMAIN).toString());
            }
            IdentityUtil.threadLocalProperties.get().remove(RE_CAPTCHA_USER_DOMAIN);
            throw new InvalidCredentialsException(ErrorMessages.INVALID_CREDENTIALS.getCode(),
                    ErrorMessages.INVALID_CREDENTIALS.getMessage(), User.getUserFromUserName(username));
        }

        //TODO: user tenant domain has to be an attribute in the AuthenticationContext
        authProperties.put("user-tenant-domain", requestTenantDomain);

        AuthenticatedUser authenticatedUser = new AuthenticatedUser(authenticationResult.getAuthenticatedUser().get());

        // Update the username from the deprecated multi attribute login feature.
        updateMultiAttributeUsername(authenticatedUser, userStoreManager);

        context.setSubject(authenticatedUser);

        String rememberMe = request.getParameter("chkRemember");
        if ("on".equals(rememberMe)) {
            context.setRememberMe(true);
        }
    }

    @Override
    protected boolean retryAuthenticationEnabled() {
        return true;
    }

    @Override
    public String getContextIdentifier(HttpServletRequest request) {
        return request.getParameter("sessionDataKey");
    }

    @Override
    public String getFriendlyName() {
        return BasicAuthenticatorConstants.AUTHENTICATOR_FRIENDLY_NAME;
    }

    @Override
    public String getName() {
        return BasicAuthenticatorConstants.AUTHENTICATOR_NAME;
    }

    private String buildErrorParamString(Map<String, String> paramMap) {

        StringBuilder params = new StringBuilder();
        for (Map.Entry<String, String> entry : paramMap.entrySet()) {
            params.append(filterAndAddParam(entry.getKey(), entry.getValue()));
        }
        return params.toString();
    }

    private String filterAndAddParam(String key, String value) {

        String keyActual = key.replaceAll("&", "").replaceAll("=", "");
        if (CollectionUtils.isNotEmpty(omittingErrorParams) && omittingErrorParams.contains(keyActual)) {
            if (log.isDebugEnabled()) {
                log.debug("omitting param " + keyActual + " in the error response.");
            }

            // The param should be omitted, hence returning empty string.
            return StringUtils.EMPTY;
        } else {
            return key + value;
        }
    }

    /**
     * Append the recaptcha related params if recaptcha is enabled for the authentication always.
     *
     * @param tenantDomain tenant domain of the application
     * @return string with the appended recaptcha params
     */
    private String getCaptchaParams(String tenantDomain) {

        SSOLoginReCaptchaConfig connector = new SSOLoginReCaptchaConfig();
        String defaultCaptchaConfigName = connector.getName() +
                CaptchaConstants.ReCaptchaConnectorPropertySuffixes.ENABLE_ALWAYS;

        String captchaParams = "";
        Property[] connectorConfigs;
        Properties captchaConfigs = getCaptchaConfigs();

        if (captchaConfigs != null && !captchaConfigs.isEmpty() &&
                Boolean.parseBoolean(captchaConfigs.getProperty(CaptchaConstants.RE_CAPTCHA_ENABLED))) {

            try {
                connectorConfigs = BasicAuthenticatorDataHolder.getInstance().getIdentityGovernanceService()
                        .getConfiguration(new String[]{defaultCaptchaConfigName, RESEND_CONFIRMATION_RECAPTCHA_ENABLE},
                                tenantDomain);

                for (Property connectorConfig : connectorConfigs) {
                    if (defaultCaptchaConfigName.equals(connectorConfig.getName())) {
                        // SSO Login Captcha Config
                        if (Boolean.parseBoolean(connectorConfig.getValue())) {
                            captchaParams = BasicAuthenticatorConstants.RECAPTCHA_PARAM + "true";
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("Enforcing recaptcha for SSO Login is not enabled.");
                            }
                        }
                    } else if ((RESEND_CONFIRMATION_RECAPTCHA_ENABLE).equals(connectorConfig.getName())) {
                        // Resend Confirmation Captcha Config
                        if (Boolean.parseBoolean(connectorConfig.getValue())) {
                            captchaParams += BasicAuthenticatorConstants.RECAPTCHA_RESEND_CONFIRMATION_PARAM + "true";
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("Enforcing recaptcha for resend confirmation is not enabled.");
                            }
                        }
                    }
                }

                // Add captcha configs
                if (!captchaParams.isEmpty()) {
                    captchaParams += BasicAuthenticatorConstants.RECAPTCHA_KEY_PARAM + captchaConfigs.getProperty
                            (CaptchaConstants.RE_CAPTCHA_SITE_KEY) +
                            BasicAuthenticatorConstants.RECAPTCHA_API_PARAM + captchaConfigs.getProperty
                            (CaptchaConstants.RE_CAPTCHA_API_URL);
                }

            } catch (IdentityGovernanceException e) {
                log.error("Error occurred while verifying the captcha configs. Proceeding the authentication request " +
                        "without enabling recaptcha.", e);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Recaptcha is not enabled.");
            }
        }

        return captchaParams;
    }

    /**
     * Get the recaptcha configs from the data holder if they are valid.
     *
     * @return recaptcha properties
     */
    private Properties getCaptchaConfigs() {

        Properties properties = BasicAuthenticatorDataHolder.getInstance().getRecaptchaConfigs();

        if (properties != null && !properties.isEmpty() &&
                Boolean.valueOf(properties.getProperty(CaptchaConstants.RE_CAPTCHA_ENABLED))) {
            if (StringUtils.isBlank(properties.getProperty(CaptchaConstants.RE_CAPTCHA_SITE_KEY)) ||
                    StringUtils.isBlank(properties.getProperty(CaptchaConstants.RE_CAPTCHA_API_URL)) ||
                    StringUtils.isBlank(properties.getProperty(CaptchaConstants.RE_CAPTCHA_SECRET_KEY)) ||
                    StringUtils.isBlank(properties.getProperty(CaptchaConstants.RE_CAPTCHA_VERIFY_URL))) {

                if (log.isDebugEnabled()) {
                    log.debug("Empty values found for the captcha properties in the file " + CaptchaConstants
                            .CAPTCHA_CONFIG_FILE_NAME + ".");
                }
                properties.clear();
            }
        }
        return properties;
    }

    private void removeAutoLoginCookie(HttpServletResponse response, Cookie autoLoginCookie)
            throws AuthenticationFailedException {

        String decodedValue = new String(Base64.getDecoder().decode(autoLoginCookie.getValue()));
        JSONObject cookieValueJSON = transformToJSON(decodedValue);
        String content = (String) cookieValueJSON.get(AutoLoginConstant.CONTENT);
        JSONObject contentJSON = transformToJSON(content);
        String domainInCookie = (String) contentJSON.get(AutoLoginConstant.DOMAIN);
        if (StringUtils.isNotEmpty(domainInCookie)) {
            autoLoginCookie.setDomain(domainInCookie);
        }

        autoLoginCookie.setMaxAge(0);
        autoLoginCookie.setValue("");
        autoLoginCookie.setPath("/");
        response.addCookie(autoLoginCookie);
    }

    private void validateCookieSignature(String content, String signature, String alias)
            throws AuthenticationFailedException {


        if (StringUtils.isEmpty(content) || StringUtils.isEmpty(signature)) {
            throw new AuthenticationFailedException("Either 'content' or 'signature' attribute is missing in value of" +
                    " Auto Login Cookie.");
        }

        try {
            boolean isSignatureValid;
            if (StringUtils.isEmpty(alias)) {
                isSignatureValid = SignatureUtil.validateSignature(content, Base64.getDecoder().decode(signature));
            } else {
                byte[] thumpPrint = SignatureUtil.getThumbPrintForAlias(alias);
                isSignatureValid = SignatureUtil.validateSignature(thumpPrint, content,
                        Base64.getDecoder().decode(signature));
            }
            if (!isSignatureValid) {
                throw new AuthenticationFailedException("Signature verification failed in Auto Login Cookie " +
                        "for user: " + content);
            }
        } catch (Exception e) {
            throw new AuthenticationFailedException("Error occurred while validating the signature for the Auto " +
                    "Login Cookie");
        }
    }


    private void validateCreatedTime(long createdTime) throws AuthenticationFailedException {

        String cookieMaxAge = AutoLoginConstant.DEFAULT_COOKIE_MAX_AGE;
        if (getAuthenticatorConfig().getParameterMap() != null) {
            String autoLoginCookieMaxAge = getAuthenticatorConfig().getParameterMap().get("AutoLoginCookieMaxAge");
            if (StringUtils.isNotEmpty(autoLoginCookieMaxAge)) {
                cookieMaxAge = autoLoginCookieMaxAge;
            }
        }
        long maxAgeTime = TimeUnit.SECONDS.toMillis(Long.parseLong(cookieMaxAge));
        long currentTime = System.currentTimeMillis();
        if (currentTime - createdTime > maxAgeTime) {
            throw new AuthenticationFailedException("The Auto Login Cookie expired.");
        }
    }

    private JSONObject transformToJSON(String value) throws AuthenticationFailedException {

        JSONParser jsonParser = new JSONParser();
        try {
            return (JSONObject) jsonParser.parse(value);
        } catch (ParseException e) {
            throw new AuthenticationFailedException("Error occurred while parsing the Auto Login Cookie JSON string " +
                    "to a JSON object", e);
        }
    }

    private boolean isEnableAutoLoginEnabled(AuthenticationContext context, Cookie autoLoginCookie)
            throws AuthenticationFailedException {

        String flowType = resolveAutoLoginFlow(autoLoginCookie.getValue());
        if (AutoLoginConstant.SIGNUP.equals(flowType)) {
            return isEnableSelfRegistrationAutoLogin(context);
        } else if (AutoLoginConstant.RECOVERY.equals(flowType)) {
            return isEnableAutoLoginAfterPasswordReset(context);
        }
        return false;
    }

    private String resolveAutoLoginFlow(String cookieValue) throws AuthenticationFailedException {

        String decodedValue = new String(Base64.getDecoder().decode(cookieValue));
        JSONObject cookieValueJSON = transformToJSON(decodedValue);
        JSONObject content = transformToJSON((String) cookieValueJSON.get(AutoLoginConstant.CONTENT));
        return (String) content.get(AutoLoginConstant.FLOW_TYPE);
    }

    private boolean isEnableAutoLoginAfterPasswordReset(AuthenticationContext context)
            throws AuthenticationFailedException {

        try {
            return Boolean.parseBoolean(
                    Utils.getConnectorConfig(AutoLoginConstant.RECOVERY_ADMIN_PASSWORD_RESET_AUTO_LOGIN,
                            context.getTenantDomain()));
        } catch (IdentityEventException e) {
            throw new AuthenticationFailedException("Error occurred while resolving isEnableAutoLogin property.", e);
        }
    }

    boolean isEnableSelfRegistrationAutoLogin(AuthenticationContext context) throws AuthenticationFailedException {

        try {
            return Boolean.parseBoolean(Utils.getConnectorConfig(AutoLoginConstant.SELF_REGISTRATION_AUTO_LOGIN,
                    context.getTenantDomain()));
        } catch (IdentityEventException e) {
            throw new AuthenticationFailedException("Error occurred while resolving isEnableSelfRegistrationAutoLogin" +
                    " property.", e);
        }
    }

    String getSelfRegistrationAutoLoginAlias(AuthenticationContext context) throws AuthenticationFailedException {

        try {
            return Utils.getConnectorConfig(AutoLoginConstant.SELF_REGISTRATION_AUTO_LOGIN_ALIAS_NAME,
                    context.getTenantDomain());
        } catch (IdentityEventException e) {
            throw new AuthenticationFailedException("Error occurred while resolving " +
                    AutoLoginConstant.SELF_REGISTRATION_AUTO_LOGIN_ALIAS_NAME + " property.", e);
        }
    }

    private void updateMultiAttributeUsername(AuthenticatedUser user, AbstractUserStoreManager userStoreManager) {

        if (getAuthenticatorConfig().getParameterMap() != null) {
            String userNameUri = getAuthenticatorConfig().getParameterMap().get("UserNameAttributeClaimUri");
            if (StringUtils.isNotBlank(userNameUri)) {
                String domain = UserCoreUtil.getDomainFromThreadLocal();
                boolean multipleAttributeEnable = isMultipleAttributeEnable(userStoreManager, domain);
                if (multipleAttributeEnable) {
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Searching for UserNameAttribute value for user " + user.getUserId() +
                                    " for claim uri : " + userNameUri);
                        }
                        // This getUserClaimValue cannot be converted to user id method, since if the value user
                        // enters is not the actual username, user id will not be available in AuthenticationResult.
                        String usernameValue = getMultiAttributeUsername(user, userStoreManager, userNameUri);
                        if (StringUtils.isNotBlank(usernameValue)) {
                            user.setUserName(usernameValue);
                            if (log.isDebugEnabled()) {
                                log.debug("UserNameAttribute is found for user + " + user.getUserId()
                                        + ". Value is: " + usernameValue);
                            }
                        }
                    } catch (UserStoreException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("Error while retrieving UserNameAttribute for user : " + user.getUserId(), e);
                        }
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("MultipleAttribute is not enabled for user store domain : " + domain + " " +
                                "Therefore UserNameAttribute is not retrieved");
                    }
                }
            }
        }
    }

    private String getMultiAttributeUsername(AuthenticatedUser user, AbstractUserStoreManager userStoreManager,
                                             String userNameUri) throws UserStoreException {

        String usernameValue;
        if (user.getUserStoreDomain() != null
                && !UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME.equals(user.getUserStoreDomain())) {
            usernameValue =
                    userStoreManager.getSecondaryUserStoreManager(user.getUserStoreDomain())
                            .getUserClaimValue(user.getUserName(), userNameUri, null);
        } else {
            usernameValue = userStoreManager.
                    getUserClaimValue(user.getUserName(), userNameUri, null);
        }
        return usernameValue;
    }

    /**
     * Check if the ldap based multi attribute login is enabled.
     *
     * @param userStoreManager Primary user store manager.
     * @param domain user store domain.
     * @return if multi attributed enabled.
     */
    private boolean isMultipleAttributeEnable(AbstractUserStoreManager userStoreManager, String domain) {

        boolean multipleAttributeEnable;
        if (StringUtils.isNotBlank(domain)) {
            multipleAttributeEnable = Boolean.parseBoolean(userStoreManager.getSecondaryUserStoreManager(domain)
                    .getRealmConfiguration().getUserStoreProperty("MultipleAttributeEnable"));
        } else {
            multipleAttributeEnable = Boolean.parseBoolean(userStoreManager.
                    getRealmConfiguration().getUserStoreProperty("MultipleAttributeEnable"));
        }
        return multipleAttributeEnable;
    }

    private AbstractUserStoreManager getUserStoreManager(String username, String tenantDomain)
            throws AuthenticationFailedException {

        try {
            int tenantId =
                    BasicAuthenticatorServiceComponent.getRealmService().getTenantManager().getTenantId(tenantDomain);
            UserRealm userRealm = BasicAuthenticatorServiceComponent.getRealmService().getTenantUserRealm(tenantId);
            if (userRealm != null) {
                return (AbstractUserStoreManager) userRealm.getUserStoreManager();
            } else {
                throw new AuthenticationFailedException("Cannot find the user realm for the given tenant: " +
                        tenantId, User.getUserFromUserName(username));
            }
        } catch (org.wso2.carbon.user.api.UserStoreException e) {
            if (log.isDebugEnabled()) {
                log.debug("Can't find the UserStoreManager for the user: " + username, e);
            }
            throw new AuthenticationFailedException(e.getMessage(), e);
        }
    }

    private Cookie getAutoLoginCookie(Cookie[] cookiesInRequest) {

        Optional<Cookie> targetCookie = Optional.empty();
        if (ArrayUtils.isNotEmpty(cookiesInRequest)) {
            targetCookie = Arrays.stream(cookiesInRequest)
                    .filter(cookie -> StringUtils.equalsIgnoreCase(AutoLoginConstant.COOKIE_NAME, cookie.getName()))
                    .filter(cookie -> StringUtils.isNotEmpty(cookie.getValue()))
                    .findFirst();
        }

        return targetCookie.orElse(null);
    }

    private boolean isAuthPolicyAccountExistCheck() {

        return Boolean.parseBoolean(IdentityUtil.getProperty(BasicAuthenticatorConstants.AUTHENTICATION_POLICY_CONFIG));
    }

    /**
     * Check user existence and set error code to IdentityErrorMsgContext if user does not exist.
     */
    private void checkUserExistence() {

        if (!isUserExist()) {
            IdentityErrorMsgContext customErrorMessageContext = new IdentityErrorMsgContext(UserCoreConstants
                    .ErrorCode.USER_DOES_NOT_EXIST);
            IdentityUtil.setIdentityErrorMsg(customErrorMessageContext);
        }
    }

    private Boolean isUserExist() {

        return IdentityUtil.threadLocalProperties.get().get(USER_EXIST_THREAD_LOCAL_PROPERTY) != null &&
                (Boolean) IdentityUtil.threadLocalProperties.get().get(USER_EXIST_THREAD_LOCAL_PROPERTY);
    }

    private void setUserExistThreadLocal() {

        IdentityUtil.threadLocalProperties.get().put(USER_EXIST_THREAD_LOCAL_PROPERTY, false);
        if (log.isDebugEnabled()) {
            log.debug(USER_EXIST_THREAD_LOCAL_PROPERTY + " is added as false to thread local.");
        }
    }

    private void clearUserExistThreadLocal() {

        IdentityUtil.threadLocalProperties.get().remove(USER_EXIST_THREAD_LOCAL_PROPERTY);
    }

    private String getTenantDomainFromUserName(AuthenticationContext context, String username) {

        boolean isSaaSApp = context.getSequenceConfig().getApplicationConfig().isSaaSApp();
        if (IdentityTenantUtil.isTenantQualifiedUrlsEnabled() && !isSaaSApp) {
            return IdentityTenantUtil.getTenantDomainFromContext();
        }
        return MultitenantUtils.getTenantDomain(username);
    }
}
