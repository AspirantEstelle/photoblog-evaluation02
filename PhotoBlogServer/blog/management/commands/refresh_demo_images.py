"""Replace only the evaluation's demo images; leave accounts and other posts intact."""
from pathlib import Path
from django.conf import settings
from django.core.files.base import ContentFile
from django.core.management.base import BaseCommand
from blog.models import Post


class Command(BaseCommand):
    help = 'Refresh photo_demo sample images using the supplied cat PNGs.'

    def handle(self, *args, **options):
        changed = 0
        for post in Post.objects.filter(author__username='photo_demo'):
            current = Path(post.image.name).name
            if not current.startswith(('pig', 'upload', 'cat_annoyed', 'cat_what')):
                continue
            filename = 'cat_what.png' if 'Android' in post.title else 'cat_annoyed.png'
            content = (settings.BASE_DIR / 'samples' / filename).read_bytes()
            if post.image:
                with post.image.open('rb') as image:
                    if image.read() == content:
                        continue
            post.image.save(filename, ContentFile(content), save=True)
            changed += 1
        self.stdout.write(self.style.SUCCESS(f'Updated {changed} demo images.'))
