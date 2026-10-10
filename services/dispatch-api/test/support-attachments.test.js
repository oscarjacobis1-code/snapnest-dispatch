import test from 'node:test';
import assert from 'node:assert/strict';
import { isAllowedSupportAttachmentRef } from '../src/modules/support-attachments.js';

test('driver support attachments stay inside their tenant and driver path', () => {
  const ref = 'storage://dispatch-support/tenant-a/driver-1/123-screenshot.jpg';
  assert.equal(isAllowedSupportAttachmentRef(ref, 'tenant-a', 'driver-1'), true);
  assert.equal(isAllowedSupportAttachmentRef(ref, 'tenant-a', 'driver-2'), false);
  assert.equal(isAllowedSupportAttachmentRef(ref, 'tenant-b', 'driver-1'), false);
});

test('dispatcher may reference any support attachment inside the same tenant', () => {
  const ref = 'storage://dispatch-support/tenant-a/driver-7/123-screenshot.jpg';
  assert.equal(isAllowedSupportAttachmentRef(ref, 'tenant-a'), true);
  assert.equal(isAllowedSupportAttachmentRef(ref, 'tenant-b'), false);
});

test('public urls and traversal-like references are rejected', () => {
  assert.equal(isAllowedSupportAttachmentRef('https://example.com/screenshot.png', 'tenant-a'), false);
  assert.equal(isAllowedSupportAttachmentRef('storage://dispatch-support/tenant-a/../tenant-b/file.png', 'tenant-a'), false);
});
