"""Run from a console or IDLE: python tools/verify_api.py [base URL]."""
import json
import sys
import urllib.request

base = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8000'
url = base.rstrip('/') + '/api_root/Post/'
with urllib.request.urlopen(url, timeout=15) as response:
    posts = json.load(response)
    print('PhotoBlog API verification')
    print('GET', url)
    print('HTTP', response.status)
    print('Content-Type:', response.headers['Content-Type'])
    print('JSON type:', type(posts).__name__, '| Posts:', len(posts))
    print(json.dumps(posts[:2], ensure_ascii=False, indent=2))
    assert isinstance(posts, list) and posts
    assert all({'id', 'title', 'text', 'image'} <= post.keys() for post in posts)
    print('\nPASS: HTTP 200 / JSON array / required fields')
