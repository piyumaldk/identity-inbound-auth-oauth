/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.identity.openidconnect;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.oltu.oauth2.as.request.OAuthAuthzRequest;
import org.wso2.carbon.identity.central.log.mgt.utils.LoggerUtils;
import org.wso2.carbon.identity.oauth.common.OAuth2ErrorCodes;
import org.wso2.carbon.identity.oauth.common.OAuthConstants;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth.dao.OAuthAppDO;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.RequestObjectException;
import org.wso2.carbon.identity.oauth2.model.OAuth2Parameters;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.identity.openidconnect.model.RequestObject;

import java.util.HashMap;
import java.util.Map;

/**
 * According to the OIDC spec requestObject is passed as a query param value of request/request_uri parameters. This is
 * associated with OIDC authorization request. This class is used to select the corresponding builder class and build
 * the request object according to the parameter.
 */
public class OIDCRequestObjectUtil {

    private static final Log log = LogFactory.getLog(OIDCRequestObjectUtil.class);
    private static final String REQUEST = "request";
    private static final String REQUEST_URI = "request_uri";
    private static final String REQUEST_PARAM_VALUE_BUILDER = "request_param_value_builder";
    private static final String REQUEST_URI_PARAM_VALUE_BUILDER = "request_uri_param_value_builder";

    /**
     * Fetch and invoke the matched request builder class based on the identity.xml configurations.
     * Build and validate the Request Object extracted from request information
     *
     * @param oauthRequest authorization request
     * @throws RequestObjectException
     */
    public static RequestObject buildRequestObject(OAuthAuthzRequest oauthRequest, OAuth2Parameters oAuth2Parameters)
            throws RequestObjectException {

        /*
          So that the request is a valid OAuth 2.0 Authorization Request, values for the response_type and client_id
          parameters MUST be included using the OAuth 2.0 request syntax, since they are REQUIRED by OAuth 2.0.
          The values for these parameters MUST match those in the Request Object, if present
         */
        RequestObject requestObject;
        RequestObjectBuilder requestObjectBuilder;
        String requestObjType;
        /*
            The order should not be changed as there can be instances where both request and request_uri can be present.
            In such cases request parameter needs to be given precedence.
         */
        if (isRequestParameter(oauthRequest)) {
            requestObjectBuilder = getRequestObjectBuilder(REQUEST_PARAM_VALUE_BUILDER);
            requestObjType = REQUEST;
        } else if (isRequestUri(oauthRequest)) {
            requestObjectBuilder = getRequestObjectBuilder(REQUEST_URI_PARAM_VALUE_BUILDER);
            requestObjType = REQUEST_URI;

        } else {
            // Unsupported request object type.
            return null;
        }

        if (requestObjectBuilder == null) {
            String error = "Unable to build the OIDC Request Object from:";
            if (LoggerUtils.isDiagnosticLogsEnabled()) {
                Map<String, Object> params = new HashMap<>();
                params.put(REQUEST, oauthRequest.getParam(REQUEST));
                params.put(REQUEST_URI, oauthRequest.getParam(REQUEST_URI));
                LoggerUtils.triggerDiagnosticLogEvent(OAuthConstants.LogConstants.OAUTH_INBOUND_SERVICE, params,
                        OAuthConstants.LogConstants.FAILED, "Server error occurred.", "parse-request-object", null);
            }
            throw new RequestObjectException(OAuth2ErrorCodes.SERVER_ERROR, error + requestObjType);
        }
        requestObject = requestObjectBuilder.buildRequestObject(oauthRequest.getParam(requestObjType),
                oAuth2Parameters);
        RequestObjectValidator requestObjectValidator = OAuthServerConfiguration.getInstance()
                .getRequestObjectValidator();

        validateRequestObjectSignature(oAuth2Parameters, requestObject, requestObjectValidator);

        if (!requestObjectValidator.validateRequestObject(requestObject, oAuth2Parameters)) {
            throw new RequestObjectException(OAuth2ErrorCodes.INVALID_REQUEST, "Invalid parameters " +
                    "found in the Request Object.");

        }
        if (log.isDebugEnabled()) {
            log.debug("Successfully build and and validated request Object for: " + requestObjType);
        }
        return requestObject;
    }

    /**
     * @param oAuth2Parameters OAuth2 parameters
     * @param requestObject OAuth2 request
     * @param requestObjectValidator OAuth2 Request validator
     * @throws RequestObjectException
     */
    public static void validateRequestObjectSignature(OAuth2Parameters oAuth2Parameters,
                                                       RequestObject requestObject,
                                                       RequestObjectValidator requestObjectValidator)
            throws RequestObjectException {

        String clientId = oAuth2Parameters.getClientId();
        OAuthAppDO oAuthAppDO;
        try {
            oAuthAppDO = OAuth2Util.getAppInformationByClientId(clientId);
        } catch (IdentityOAuth2Exception | InvalidOAuthClientException e) {
            LoggerUtils.triggerDiagnosticLogEvent(OAuthConstants.LogConstants.OAUTH_INBOUND_SERVICE, null,
                    OAuthConstants.LogConstants.FAILED, "Server error occurred.", "validate-request-object-signature",
                    null);
            throw new RequestObjectException("Error while retrieving app information for client_id: " + clientId +
                    ". Cannot proceed with signature validation", e);
        }

        try {
            // Check whether request object signature validation is enforced.
            if (oAuthAppDO.isRequestObjectSignatureValidationEnabled()) {
                if (log.isDebugEnabled()) {
                    log.debug("Request Object Signature Verification enabled for client_id: " + clientId);
                }
                if (requestObject.isSigned()) {
                    validateSignature(oAuth2Parameters, requestObject, requestObjectValidator);
                } else {
                    // If request object is not signed we need to throw an exception.
                    if (LoggerUtils.isDiagnosticLogsEnabled()) {
                        Map<String, Object> params = new HashMap<>();
                        params.put("clientId", clientId);

                        Map<String, Object> configs = new HashMap<>();
                        configs.put("requestObjectSignatureValidationEnabled", "true");
                        LoggerUtils.triggerDiagnosticLogEvent(OAuthConstants.LogConstants.OAUTH_INBOUND_SERVICE, params,
                                OAuthConstants.LogConstants.FAILED,
                                "Request object signature validation is enabled but request object is not signed.",
                                "validate-request-object-signature", configs);
                    }
                    throw new RequestObjectException("Request object signature validation is enabled but request " +
                            "object is not signed.");
                }
            } else {
                // Since request object signature validation is not enabled we will only validate the signature if
                // the request object is signed.
                if (requestObject.isSigned()) {
                    validateSignature(oAuth2Parameters, requestObject, requestObjectValidator);
                }
            }
        } catch (RequestObjectException e) {
            if (StringUtils.isNotBlank(e.getErrorMessage()) && e.getErrorMessage().contains("signature verification " +
                    "failed")) {
                if (LoggerUtils.isDiagnosticLogsEnabled()) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("clientId", oAuth2Parameters.getClientId());

                    Map<String, Object> configs = new HashMap<>();
                    configs.put("requestObjectSignatureValidationEnabled",
                            Boolean.toString(oAuthAppDO.isRequestObjectSignatureValidationEnabled()));
                    LoggerUtils.triggerDiagnosticLogEvent(OAuthConstants.LogConstants.OAUTH_INBOUND_SERVICE, params,
                            OAuthConstants.LogConstants.FAILED, "Request Object signature verification failed.",
                            "validate-request-object-signature", configs);
                }
            }
            throw e;
        }
        LoggerUtils.triggerDiagnosticLogEvent(OAuthConstants.LogConstants.OAUTH_INBOUND_SERVICE, null,
                OAuthConstants.LogConstants.SUCCESS, "Request Object signature verification is successful.",
                "validate-request-object-signature", null);
    }

    private static void validateSignature(OAuth2Parameters oAuth2Parameters,
                                          RequestObject requestObject,
                                          RequestObjectValidator requestObjectValidator) throws RequestObjectException {

        if (!requestObjectValidator.validateSignature(requestObject, oAuth2Parameters)) {
            throw new RequestObjectException(OAuth2ErrorCodes.INVALID_REQUEST,
                    "Request Object signature verification failed.");
        }
    }

    private static RequestObjectBuilder getRequestObjectBuilder(String requestParamValueBuilder) {

        return OAuthServerConfiguration.getInstance().getRequestObjectBuilders().get(requestParamValueBuilder);
    }

    private static boolean isRequestUri(OAuthAuthzRequest oAuthAuthzRequest) {

        return StringUtils.isNotBlank(oAuthAuthzRequest.getParam(REQUEST_URI));
    }

    private static boolean isRequestParameter(OAuthAuthzRequest oAuthAuthzRequest) {

        return StringUtils.isNotBlank(oAuthAuthzRequest.getParam(REQUEST));
    }
}
