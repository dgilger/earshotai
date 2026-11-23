// The token is in the part after '#', which the browser never sends to the server.
  const token = decodeURIComponent(location.hash.slice(1));
  const url = location.origin;
  const go = document.getElementById('go');
  if (!token) {
    document.getElementById('err').textContent = 'This link has no token. Scan the code on the Connect page again.';
    go.style.display = 'none';
  } else {
    const q = 'url=' + encodeURIComponent(url) + '&token=' + encodeURIComponent(token);
    // Android: an intent link opens the app directly. Other phones: the earshot:// link.
    go.href = /Android/i.test(navigator.userAgent)
      ? 'intent://pair?' + q + '#Intent;scheme=earshot;package=app.earshot;end'
      : 'earshot://pair?' + q;
  }
