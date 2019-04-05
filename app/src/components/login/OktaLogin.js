import React, { Component } from 'react';

import * as OktaSignIn from '@okta/okta-signin-widget';
import '@okta/okta-signin-widget/dist/css/okta-sign-in.min.css';
import '@okta/okta-signin-widget/dist/css/okta-theme.css';

import './OktaLogin.css';
import config from './OktaConfig';

export default class OktaLogin extends Component {
  constructor(props) {
    super(props);
    this.signIn = new OktaSignIn({
      /**
       * Note: when using the Sign-In Widget for an OIDC flow, it still
       * needs to be configured with the base URL for your Okta Org. Here
       * we derive it from the given issuer for convenience.
       */
      idps: [
        {type: 'GOOGLE', id: 'wakanda-1551856072419'}
      ],
      baseUrl: config.oidc.issuer.split('/oauth2')[0],
      clientId: config.oidc.clientId,
      redirectUri: config.oidc.redirectUri,
      logo: '/react.svg',
      i18n: {
        en: {
          'primaryauth.title': 'Sign in to Wakanda',
        },
      },
      authParams: {
        responseType: ['id_token', 'token'],
        issuer: config.oidc.issuer,
        display: 'page',
        scopes: config.oidc.scope.split(' '),
      },
    });
  }

  state = {};

  componentDidMount() {
    this.signIn.renderEl(
      { el: '#sign-in-widget' },
      () => {
        /**
         * In this flow, the success handler will not be called beacuse we redirect
         * to the Okta org for the authentication workflow.
         */
      },
      (err) => {
        throw err;
      },
    );
  }

  render() {
    return (
    <div>
      <div id="sign-in-widget" />
    </div>);
  }
}