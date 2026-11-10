import io
import shutil
import sqlite3
import tempfile
import uuid
from contextlib import closing
from pathlib import Path
from PIL import Image
from django.contrib.auth import get_user_model
from django.core.management import call_command
from django.core.files.uploadedfile import SimpleUploadedFile
from django.db import connections
from django.test import SimpleTestCase, TestCase, override_settings
from django.urls import reverse
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework.authtoken.models import Token
from .models import Post


def picture(name='example.png'):
    stream = io.BytesIO()
    Image.new('RGB', (32, 32), '#3974b9').save(stream, 'PNG')
    return SimpleUploadedFile(name, stream.getvalue(), content_type='image/png')


class SQLiteCommitLockTests(SimpleTestCase):
    databases = {'default'}

    def test_locked_commit_cleans_only_its_image_and_retry_preserves_both_posts(self):
        from .models import UploadReceipt

        # An in-memory SQLite database cannot reproduce the file-level reader
        # lock that blocks COMMIT after the JPEG and both rows were written.
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        previous = connections['default']
        database = previous.copy()
        database.settings_dict.update(NAME=str(root / 'test.sqlite3'), OPTIONS={'timeout': 0.1})
        connections['default'] = database
        self.addCleanup(connections.__setitem__, 'default', previous)
        self.addCleanup(database.close)
        call_command('migrate', verbosity=0)
        api = APIClient()
        api.force_authenticate(get_user_model().objects.create_user('camera'))
        media = root / 'media'

        def upload(event_id, title):
            return api.post('/api_root/Post/', {'title': title, 'text': 'camera', 'image': picture('camera.png')},
                            format='multipart', HTTP_IDEMPOTENCY_KEY=str(event_id))

        def stored_files():
            return {path.relative_to(media): path.read_bytes() for path in media.rglob('*.png')}

        with override_settings(MEDIA_ROOT=media):
            self.assertEqual(upload(uuid.uuid4(), 'first').status_code, 201)
            before = stored_files()
            self.assertEqual(len(before), 1)
            event_id = uuid.uuid4()
            with closing(sqlite3.connect(database.settings_dict['NAME'])) as reader:
                reader.execute('BEGIN')
                reader.execute('SELECT id FROM blog_post').fetchall()
                response = upload(event_id, 'second')
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response['Retry-After'], '1')
            self.assertEqual(stored_files(), before)
            self.assertEqual(Post.objects.count(), 1)
            self.assertFalse(UploadReceipt.objects.filter(event_id=event_id).exists())

            created = upload(event_id, 'second')
            self.assertEqual(created.status_code, 201)
            replay = upload(event_id, 'second')
            self.assertEqual(replay.status_code, 200)
            self.assertEqual(replay.data['id'], created.data['id'])
            self.assertEqual(Post.objects.count(), 2)
            self.assertEqual(UploadReceipt.objects.count(), 2)
            second_image = Path(Post.objects.get(pk=created.data['id']).image.name)
            self.assertNotIn(second_image, before)
            self.assertEqual(stored_files(), {**before, second_image: picture().read()})


class PhotoBlogTests(TestCase):
    @classmethod
    def setUpClass(cls):
        cls.media = tempfile.mkdtemp()
        cls.override = override_settings(MEDIA_ROOT=cls.media)
        cls.override.enable()
        super().setUpClass()

    @classmethod
    def tearDownClass(cls):
        super().tearDownClass()
        cls.override.disable()
        shutil.rmtree(cls.media)

    def setUp(self):
        self.owner = get_user_model().objects.create_user('owner', password='test-pass-123')
        self.other = get_user_model().objects.create_user('other', password='test-pass-456')
        self.post = Post.objects.create(author=self.owner, title='첫 사진', text='본문',
                                       published_date=timezone.now(), image=picture())
        self.api = APIClient()

    def login_api(self, user=None):
        token, _ = Token.objects.get_or_create(user=user or self.owner)
        self.api.credentials(HTTP_AUTHORIZATION='Token ' + token.key)

    def test_list_is_array_with_absolute_image_url(self):
        result = self.api.get('/api_root/Post/')
        self.assertEqual(result.status_code, 200)
        self.assertIsInstance(result.json(), list)
        self.assertEqual(result.json()[0]['title'], '첫 사진')
        self.assertTrue(result.json()[0]['image'].startswith('http://testserver/media/'))

    def test_detail_and_not_found(self):
        self.assertEqual(self.api.get(f'/api_root/Post/{self.post.pk}/').status_code, 200)
        self.assertEqual(self.api.get('/api_root/Post/99999/').status_code, 404)

    def test_anonymous_write_rejected(self):
        result = self.api.post('/api_root/Post/', {'title': 'anonymous', 'text': 'x', 'image': picture()})
        self.assertEqual(result.status_code, 401)

    def test_multipart_create_saves_image_and_owner(self):
        self.login_api()
        result = self.api.post('/api_root/Post/', {'title': 'Android 업로드', 'text': 'UTF-8 본문',
                            'author': self.other.pk, 'image': picture()}, format='multipart')
        self.assertEqual(result.status_code, 201, result.data)
        created = Post.objects.get(pk=result.data['id'])
        self.assertEqual(created.author, self.owner)
        self.assertIsNotNone(created.published_date)
        with created.image.open('rb') as saved:
            self.assertEqual(Image.open(saved).size, (32, 32))

    def test_missing_image_and_blank_title(self):
        self.login_api()
        result = self.api.post('/api_root/Post/', {'title': '', 'text': 'x'})
        self.assertEqual(result.status_code, 400)
        self.assertIn('title', result.data)
        self.assertIn('image', result.data)

    def test_invalid_image_rejected(self):
        self.login_api()
        fake = SimpleUploadedFile('fake.png', b'not an image', content_type='image/png')
        result = self.api.post('/api_root/Post/', {'title': 'fake', 'text': 'x', 'image': fake})
        self.assertEqual(result.status_code, 400)

    def test_oversized_and_unsupported_image(self):
        from .serializers import PostSerializer
        from django.core.exceptions import ValidationError
        large = picture()
        large.size = 6 * 1024 * 1024
        with self.assertRaises(ValidationError):
            PostSerializer().validate_image(large)
        data = io.BytesIO()
        Image.new('RGB', (8, 8)).save(data, 'BMP')
        self.login_api()
        result = self.api.post('/api_root/Post/', {'title': 'bmp', 'text': 'x',
                 'image': SimpleUploadedFile('a.bmp', data.getvalue(), content_type='image/bmp')})
        self.assertEqual(result.status_code, 400)

    def test_real_oversized_upload_rejected_by_web_and_api(self):
        self.login_api()
        self.client.force_login(self.owner)
        payload = picture().read() + b'\0' * (5 * 1024 * 1024)
        for client, url in [(self.api, '/api_root/Post/'), (self.client, '/post/new/')]:
            with self.subTest(url=url):
                response = client.post(url, {'title': 'large', 'text': '본문',
                    'image': SimpleUploadedFile('large.png', payload, content_type='image/png')})
                self.assertEqual(response.status_code, 400 if client is self.api else 200)
                self.assertContains(response, '5 MB', status_code=response.status_code)
        self.assertEqual(Post.objects.count(), 1)

    def test_web_rejects_image_format_the_app_cannot_display(self):
        self.client.force_login(self.owner)
        data = io.BytesIO()
        Image.new('RGB', (8, 8)).save(data, 'BMP')
        response = self.client.post('/post/new/', {'title': 'bmp', 'text': '본문',
            'image': SimpleUploadedFile('photo.bmp', data.getvalue(), content_type='image/bmp')})
        self.assertContains(response, 'JPG, PNG, GIF, WEBP')
        self.assertEqual(Post.objects.count(), 1)

    def test_owner_can_update_and_delete(self):
        self.login_api()
        url = f'/api_root/Post/{self.post.pk}/'
        self.assertEqual(self.api.patch(url, {'title': '수정'}).status_code, 200)
        self.assertEqual(self.api.delete(url).status_code, 204)
        self.assertFalse(Post.objects.filter(pk=self.post.pk).exists())

    def test_other_author_cannot_update_or_delete(self):
        self.login_api(self.other)
        url = f'/api_root/Post/{self.post.pk}/'
        self.assertEqual(self.api.patch(url, {'title': 'x'}).status_code, 403)
        self.assertEqual(self.api.delete(url).status_code, 403)

    def test_staff_can_manage(self):
        self.other.is_staff = True
        self.other.save()
        self.login_api(self.other)
        self.assertEqual(self.api.patch(f'/api_root/Post/{self.post.pk}/', {'title': '관리'}).status_code, 200)

    def test_search_and_options(self):
        self.assertEqual(len(self.api.get('/api_root/Post/?search=없는검색').json()), 0)
        self.assertEqual(len(self.api.get('/api_root/Post/?search=사진').json()), 1)
        self.assertEqual(self.api.options('/api_root/Post/').status_code, 200)
        self.assertEqual(self.api.head('/api_root/Post/').status_code, 200)

    def test_token_login(self):
        result = self.api.post('/api-token-auth/', {'username': 'owner', 'password': 'test-pass-123'})
        self.assertEqual(result.status_code, 200)
        self.assertIn('token', result.data)
        self.assertEqual(self.api.post('/api-token-auth/', {'username': 'owner', 'password': 'bad'}).status_code, 400)

    def test_web_list_search_detail(self):
        self.assertContains(self.client.get('/'), '첫 사진')
        self.assertNotContains(self.client.get('/?q=없는검색'), '첫 사진')
        self.assertContains(self.client.get(f'/post/{self.post.pk}/'), '<img', html=False)

    def test_web_create_edit_and_permissions(self):
        self.assertEqual(self.client.get('/post/new/').status_code, 302)
        self.client.force_login(self.owner)
        self.assertContains(self.client.get('/post/new/'), 'multipart/form-data')
        self.assertEqual(self.client.post('/post/new/', {'title': '웹 업로드', 'text': '본문', 'image': picture()}).status_code, 302)
        url = f'/post/{self.post.pk}/edit/'
        self.assertEqual(self.client.get(url).status_code, 200)
        self.assertEqual(self.client.post(url, {'title': '웹 수정', 'text': '바뀐 본문'}).status_code, 302)
        self.client.force_login(self.other)
        self.assertEqual(self.client.get(url).status_code, 403)

    def test_model_publish_and_string(self):
        self.post.published_date = None
        self.post.publish()
        self.assertEqual(str(self.post), '첫 사진')
        self.assertIsNotNone(self.post.published_date)


class ConditionalDeliveryTests(TestCase):
    """Cache correctness and at-least-once edge delivery with one stored post."""

    @classmethod
    def setUpClass(cls):
        cls.media = tempfile.mkdtemp()
        cls.override = override_settings(MEDIA_ROOT=cls.media)
        cls.override.enable()
        super().setUpClass()

    @classmethod
    def tearDownClass(cls):
        super().tearDownClass()
        cls.override.disable()
        shutil.rmtree(cls.media)

    def setUp(self):
        import uuid
        self.owner = get_user_model().objects.create_user('edge-owner', password='edge-test-123')
        self.other = get_user_model().objects.create_user('edge-other', password='edge-test-456')
        self.api = APIClient()
        self.api.force_authenticate(self.owner)
        self.key = str(uuid.uuid4())
        self.url = '/api_root/Post/'

    def submit(self, key=None, **fields):
        payload = {'title': 'YOLO person detection', 'text': 'camera-01 / person: 0.91', 'image': picture()}
        payload.update(fields)
        return self.api.post(self.url, payload, format='multipart',
                             HTTP_IDEMPOTENCY_KEY=key or self.key)

    def test_replayed_event_keeps_one_post_and_its_exact_image(self):
        from .models import UploadReceipt
        first = self.submit()
        self.assertEqual(first.status_code, 201, first.data)
        second = self.submit(image=picture('different-filename.png'))
        self.assertEqual(second.status_code, 200, second.data)
        self.assertEqual(first.json(), second.json())
        self.assertEqual(first['Idempotency-Replayed'], 'false')
        self.assertEqual(second['Idempotency-Replayed'], 'true')
        self.assertEqual(Post.objects.count(), 1)
        self.assertEqual(UploadReceipt.objects.count(), 1)
        with Post.objects.get().image.open('rb') as saved:
            self.assertEqual(saved.read(), picture().read())

    def test_same_event_different_payload_is_conflict(self):
        self.assertEqual(self.submit().status_code, 201)
        changed = io.BytesIO()
        Image.new('RGB', (32, 32), '#e04336').save(changed, 'PNG')
        cases = [
            {'title': 'Changed title'}, {'text': 'Changed detection text'},
            {'published_date': '2026-11-05T12:00:00+09:00'},
            {'image': SimpleUploadedFile('changed.png', changed.getvalue(), content_type='image/png')},
        ]
        for fields in cases:
            with self.subTest(fields=list(fields)):
                self.assertEqual(self.submit(**fields).status_code, 409)
        self.assertEqual(Post.objects.count(), 1)

    def test_idempotency_ignores_read_only_author_and_creation_date(self):
        first = self.submit()
        replay = self.submit(author=self.other.pk, created_date='2000-01-01T00:00:00Z')
        self.assertEqual(replay.status_code, 200)
        self.assertEqual(replay.data['id'], first.data['id'])
        self.assertEqual(Post.objects.get().author, self.owner)

    def test_same_uuid_is_scoped_to_each_authenticated_owner(self):
        first = self.submit()
        self.api.force_authenticate(self.other)
        second = self.submit()
        self.assertEqual(second.status_code, 201)
        self.assertNotEqual(first.data['id'], second.data['id'])
        self.assertEqual(Post.objects.count(), 2)

    def test_bad_keys_and_unauthenticated_delivery_are_rejected(self):
        from .models import UploadReceipt
        for key in ['not-a-uuid', '123']:
            self.assertEqual(self.submit(key=key).status_code, 400)
        self.api.force_authenticate(None)
        self.assertEqual(self.submit().status_code, 401)
        self.assertFalse(Post.objects.exists())
        self.assertFalse(UploadReceipt.objects.exists())

    def test_validation_failure_does_not_consume_the_event_key(self):
        from .models import UploadReceipt
        self.assertEqual(self.submit(title='').status_code, 400)
        self.assertFalse(UploadReceipt.objects.exists())
        self.assertEqual(self.submit().status_code, 201)

    def test_deleted_event_is_not_recreated_by_a_late_retry(self):
        from .models import UploadReceipt
        result = self.submit()
        self.assertEqual(self.api.delete(f"{self.url}{result.data['id']}/").status_code, 204)
        self.assertEqual(self.submit().status_code, 410)
        self.assertFalse(Post.objects.exists())
        self.assertIsNone(UploadReceipt.objects.get().post_id)

    def test_normal_post_without_key_remains_supported(self):
        for _ in range(2):
            response = self.api.post(self.url, {'title': 'Normal', 'text': 'Post', 'image': picture()}, format='multipart')
            self.assertEqual(response.status_code, 201)
            self.assertNotIn('Idempotency-Replayed', response)
        self.assertEqual(Post.objects.count(), 2)

    def test_temporary_database_lock_rolls_back_event_key_for_retry(self):
        from unittest.mock import patch
        from django.db import OperationalError
        from .models import UploadReceipt
        with patch('blog.views.BlogImages.perform_create', side_effect=OperationalError('database is locked')):
            response = self.submit()
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response['Retry-After'], '1')
        self.assertFalse(UploadReceipt.objects.exists())
        self.assertEqual(self.submit().status_code, 201)

    def test_etag_matches_actual_json_and_304_has_no_body(self):
        import hashlib
        self.submit()
        self.api.force_authenticate(None)
        first = self.api.get(self.url)
        self.assertEqual(first['ETag'], '"' + hashlib.sha256(first.content).hexdigest() + '"')
        self.assertEqual(first['Cache-Control'], 'private, no-cache')
        self.assertTrue({'Accept', 'Authorization', 'Cookie'} <= set(first['Vary'].split(', ')))
        second = self.api.get(self.url, HTTP_IF_NONE_MATCH=first['ETag'])
        self.assertEqual(second.status_code, 304)
        self.assertEqual(second.content, b'')
        self.assertEqual(second['ETag'], first['ETag'])

    def test_weak_listed_and_wildcard_validators(self):
        tag = self.api.get(self.url)['ETag']
        for validator in ['W/' + tag, '"unrelated", ' + tag, '*']:
            with self.subTest(validator=validator):
                self.assertEqual(self.api.get(self.url, HTTP_IF_NONE_MATCH=validator).status_code, 304)
        self.assertEqual(self.api.get(self.url, HTTP_IF_NONE_MATCH='"old"').status_code, 200)

    def test_head_and_detail_etags(self):
        post = self.submit()
        detail = f"{self.url}{post.data['id']}/"
        tag = self.api.get(detail)['ETag']
        self.assertEqual(self.api.head(detail)['ETag'], tag)
        self.assertEqual(self.api.head(detail, HTTP_IF_NONE_MATCH=tag).status_code, 304)
        self.assertEqual(self.api.get(self.url + '9999/', HTTP_IF_NONE_MATCH='*').status_code, 404)

    def test_edit_image_and_delete_invalidate_list_and_detail(self):
        post = self.submit()
        detail = f"{self.url}{post.data['id']}/"
        for change in [{'title': 'New title'}, {'text': 'New body'}, {'image': picture()}]:
            before_list, before_detail = self.api.get(self.url)['ETag'], self.api.get(detail)['ETag']
            self.assertEqual(self.api.patch(detail, change, format='multipart').status_code, 200)
            for url, tag in [(self.url, before_list), (detail, before_detail)]:
                response = self.api.get(url, HTTP_IF_NONE_MATCH=tag)
                self.assertEqual(response.status_code, 200)
                self.assertNotEqual(response['ETag'], tag)
        before = self.api.get(self.url)['ETag']
        self.assertEqual(self.api.delete(detail).status_code, 204)
        self.assertEqual(self.api.get(self.url, HTTP_IF_NONE_MATCH=before).status_code, 200)
        self.assertEqual(self.api.get(detail).status_code, 404)

    def test_new_post_and_search_produce_correct_representations(self):
        empty = self.api.get(self.url)['ETag']
        self.submit()
        full = self.api.get(self.url, HTTP_IF_NONE_MATCH=empty)
        self.assertEqual(full.status_code, 200)
        filtered = self.api.get(self.url + '?search=not-present', HTTP_IF_NONE_MATCH=full['ETag'])
        self.assertEqual(filtered.status_code, 200)
        self.assertEqual(filtered.json(), [])

    def test_browsable_html_is_never_revalidated_as_json(self):
        tag = self.api.get(self.url)['ETag']
        html = self.api.get(self.url, HTTP_ACCEPT='text/html', HTTP_IF_NONE_MATCH=tag)
        self.assertEqual(html.status_code, 200)
        self.assertNotIn('ETag', html)
        self.assertEqual(html['Cache-Control'], 'private, no-cache')

    def test_invalid_authentication_is_not_hidden_by_cached_response(self):
        tag = self.api.get(self.url)['ETag']
        self.api.force_authenticate(None)
        self.api.credentials(HTTP_AUTHORIZATION='Token invalid-token')
        self.assertEqual(self.api.get(self.url, HTTP_IF_NONE_MATCH=tag).status_code, 401)
