#!/bin/sh
# usage: run-itest.sh  -> prints failures and the RESULT line from itest.html (headless Chrome)
/usr/bin/google-chrome --headless=new --no-sandbox --disable-gpu --allow-file-access-from-files --virtual-time-budget=240000 --dump-dom file:///home/claude/SwingEdge/shots/itest.html 2>/dev/null > /tmp/it.html
sed -n '/<pre/,/<\/pre>/p' /tmp/it.html | grep -v "^ok" | sed 's/<[^>]*>//g'
