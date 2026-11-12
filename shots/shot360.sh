#!/bin/sh
# usage: shot360.sh NAME "query" [budget=6000]  -> true 360 px wide iframe, 500x780 window
B=${3:-6000}
/usr/bin/google-chrome --headless=new --no-sandbox --disable-gpu --hide-scrollbars --virtual-time-budget=$B --window-size=500,780 --screenshot=/home/claude/SwingEdge/shots/$1.png "file:///home/claude/SwingEdge/shots/w360.html?$2" >/dev/null 2>&1
