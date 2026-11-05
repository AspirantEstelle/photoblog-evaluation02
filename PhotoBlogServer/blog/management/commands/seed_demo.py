"""Create isolated example data; never replace an existing account password."""
import json
import secrets
from pathlib import Path
from django.conf import settings
from django.contrib.auth import get_user_model
from django.core.files import File
from django.core.management.base import BaseCommand
from django.utils import timezone
from rest_framework.authtoken.models import Token
from blog.models import Post


class Command(BaseCommand):
    help = 'Create a demo author, private credentials and one sample post.'

    def handle(self, *args, **options):
        credentials = settings.BASE_DIR / '.demo-credentials.json'
        user, created = get_user_model().objects.get_or_create(username='photo_demo')
        if created:
            password = secrets.token_urlsafe(24)
            user.set_password(password)
            user.save()
            token, _ = Token.objects.get_or_create(user=user)
            credentials.write_text(json.dumps({'username': user.username, 'password': password,
                                               'token': token.key}), encoding='utf-8')
            credentials.chmod(0o600)
        if not Post.objects.filter(author=user).exists():
            with (settings.BASE_DIR / 'samples' / 'cat_annoyed.png').open('rb') as image:
                Post.objects.create(author=user, title='PhotoBlog 첫 번째 사진',
                                    text='Django API와 Android PhotoViewer의 이미지 동기화 실습입니다.',
                                    published_date=timezone.now(), image=File(image, name='cat_annoyed.png'))
        self.stdout.write(self.style.SUCCESS('Demo data ready. Credentials are in the ignored private file.'))
