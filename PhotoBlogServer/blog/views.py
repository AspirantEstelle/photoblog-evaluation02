import hashlib
import uuid

from django.contrib.auth.decorators import login_required
from django.db import OperationalError, transaction
from django.db.models import Q
from django.http import HttpResponseForbidden
from django.shortcuts import render, get_object_or_404, redirect
from django.utils import timezone
from django.utils.cache import patch_vary_headers
from django.utils.http import parse_etags
from rest_framework import viewsets, filters
from rest_framework.permissions import IsAuthenticatedOrReadOnly
from rest_framework.response import Response
from .delivery import payload_digest
from .forms import PostForm
from .models import Post, UploadReceipt
from .permissions import IsAuthorOrReadOnly
from .serializers import PostSerializer

def post_list(request):
    posts = Post.objects.filter(published_date__lte=timezone.now()).order_by('-published_date', '-pk')
    query = request.GET.get('q', '').strip()
    if query:
        posts = posts.filter(Q(title__icontains=query) | Q(text__icontains=query))
    return render(request, 'blog/post_list.html', {'posts': posts, 'query': query})

def post_detail(request, pk):
    return render(request, 'blog/post_detail.html', {'post': get_object_or_404(Post, pk=pk)})

@login_required
def post_new(request):
    form = PostForm(request.POST or None, request.FILES or None)
    if request.method == 'POST' and form.is_valid():
        post = form.save(commit=False)
        post.author = request.user
        post.published_date = timezone.now()
        post.save()
        return redirect('post_detail', pk=post.pk)
    return render(request, 'blog/post_edit.html', {'form': form})

@login_required
def post_edit(request, pk):
    post = get_object_or_404(Post, pk=pk)
    if post.author_id != request.user.id and not request.user.is_staff:
        return HttpResponseForbidden('작성자만 수정할 수 있습니다.')
    form = PostForm(request.POST or None, request.FILES or None, instance=post)
    if request.method == 'POST' and form.is_valid():
        form.save()
        return redirect('post_detail', pk=post.pk)
    return render(request, 'blog/post_edit.html', {'form': form})

class BlogImages(viewsets.ModelViewSet):
    queryset = Post.objects.select_related('author').order_by('-published_date', '-pk')
    serializer_class = PostSerializer
    permission_classes = [IsAuthenticatedOrReadOnly, IsAuthorOrReadOnly]
    filter_backends = [filters.SearchFilter]
    search_fields = ['title', 'text']

    def conditional_response(self, response):
        # HTML includes per-session forms. Only the selected JSON representation
        # gets a validator; private revalidation avoids sharing authenticated data.
        response['Cache-Control'] = 'private, no-cache'
        patch_vary_headers(response, ('Accept', 'Authorization', 'Cookie'))
        if self.request.accepted_renderer.format != 'json':
            return response
        context = self.get_renderer_context()
        context['response'] = response
        body = self.request.accepted_renderer.render(
            response.data, self.request.accepted_media_type, context)
        etag = '"' + hashlib.sha256(body).hexdigest() + '"'
        response['ETag'] = etag
        validators = parse_etags(self.request.headers.get('If-None-Match', ''))
        if '*' in validators or any(tag.removeprefix('W/') == etag for tag in validators):
            return Response(status=304, headers={
                'ETag': etag, 'Cache-Control': response['Cache-Control'],
                'Vary': response['Vary'],
            })
        return response

    def list(self, request, *args, **kwargs):
        return self.conditional_response(super().list(request, *args, **kwargs))

    def retrieve(self, request, *args, **kwargs):
        return self.conditional_response(super().retrieve(request, *args, **kwargs))

    def create(self, request, *args, **kwargs):
        key = request.headers.get('Idempotency-Key')
        if key is None:
            return super().create(request, *args, **kwargs)
        try:
            event_id = uuid.UUID(key)
        except (ValueError, AttributeError):
            return Response({'detail': 'Idempotency-Key must be a UUID.'}, status=400)
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        digest = payload_digest(serializer.validated_data)
        try:
            with transaction.atomic():
                receipt, created = UploadReceipt.objects.get_or_create(
                    author=request.user, event_id=event_id,
                    defaults={'request_digest': digest})
                if not created:
                    if receipt.request_digest != digest:
                        return Response({'detail': 'This event key already belongs to a different payload.'}, status=409)
                    if receipt.post_id is None:
                        return Response({'detail': 'This event was accepted, but its post has been deleted.'}, status=410)
                    return Response(self.get_serializer(receipt.post).data, status=200,
                                    headers={'Idempotency-Replayed': 'true'})
                self.perform_create(serializer)
                receipt.post = serializer.instance
                receipt.save(update_fields=['post'])
                headers = self.get_success_headers(serializer.data)
                headers['Idempotency-Replayed'] = 'false'
                return Response(serializer.data, status=201, headers=headers)
        except OperationalError as error:
            # SQLite serializes writers. A concurrent edge sender can safely
            # retry its unchanged UUID rather than lose or duplicate the event.
            if 'locked' not in str(error).lower():
                raise
            return Response({'detail': 'Another upload is being saved. Retry this same event key.'},
                            status=503, headers={'Retry-After': '1'})

    def perform_create(self, serializer):
        serializer.save(author=self.request.user, published_date=serializer.validated_data.get('published_date') or timezone.now())
