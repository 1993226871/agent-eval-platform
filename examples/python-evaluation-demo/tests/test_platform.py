import unittest

import httpx

from order_agent.platform import Platform, PlatformError


class PlatformTests(unittest.TestCase):
    def test_csrf_cookie_and_fresh_token_are_used(self):
        seen = []

        def respond(request):
            seen.append(request)
            if request.url.path.endswith('/csrf'):
                return httpx.Response(200, json={'header_name': 'X-CSRF-TOKEN', 'token': 'nonce'},
                                      headers={'set-cookie': 'SESSION=fake; Secure; Path=/'})
            self.assertEqual(request.headers['X-CSRF-TOKEN'], 'nonce')
            self.assertIn('SESSION=fake', request.headers['cookie'])
            return httpx.Response(201, json={'id': 'new-agent'})

        api = Platform('https://localhost', None, httpx.MockTransport(respond))
        try:
            self.assertEqual(api.request('POST', '/agents', {'name': 'demo'})['id'], 'new-agent')
            self.assertEqual([r.url.path for r in seen], ['/api/v1/auth/csrf', '/api/v1/agents'])
        finally:
            api.client.close()

    def test_rejection_does_not_echo_response_secret(self):
        api = Platform('https://localhost', None, httpx.MockTransport(
            lambda _: httpx.Response(403, text='private-token-and-password')))
        try:
            with self.assertRaisesRegex(PlatformError, '^Platform request rejected: HTTP 403$'):
                api.request('GET', '/auth/me')
        finally:
            api.client.close()

    def test_remote_http_and_embedded_password_are_rejected(self):
        for url in ['http://example.com', 'https://user:secret@example.com', 'https://localhost/api']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                Platform(url, None)


if __name__ == '__main__':
    unittest.main()
