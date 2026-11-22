#!/usr/bin/env python3
"""Dedicated screens, script S2: the user has an App the thread holds on the main screen. Covers B3 and C4 (design
doc section 2).

The user decided the agent may take an App over at any time: an App open on the main screen or in Recents never
blocks the thread (no USER_HOLDS_APP). The user is simulated from the shell: `am start --display 0` opens an App on
the main screen the way tapping its icon does, and `am stack remove` swipes its task away in Recents. Three parts:

1. APP (TAKU_S2_APP, default Clock) is in front on the main screen before the thread asks for it: the request is
   granted, tap and open_app run at once, and APP ends up on the thread's screen (its own task, or the user's task
   moved over for a single-instance App).
2. The user opens Settings while the thread uses it: Android moves the thread's task to the main screen; the next
   tap still runs and open_app brings Settings back to the thread's screen.
3. C4: after the turn ends, the user opens Settings and gets a new task, not the thread's page.

Needs what S1 needs.

    python3 system/assistant/tests/dedicated-screens/e2e/s2.py [serial]
"""
import os, sys, uuid

import s1
from s1 import CLOCK, SETTINGS, apps_on, check, shell, tasks, tool, turn_ended

APP = os.environ.get('TAKU_S2_APP', CLOCK)


def task_of(pkg, display):
    """pkg's top task id on the display, or None."""
    return next((i for i, p, d in tasks() if p == pkg and d == display), None)


def user_opens(pkg):
    """Opens pkg on the main screen as its icon would; returns its task id there."""
    component = shell(f'cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {pkg}').split()[-1]
    shell(f'am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {component}')
    return s1.until(lambda: task_of(pkg, 0), f'{pkg} on the main screen: {tasks()}')


def user_swipes(task):
    shell(f'am stack remove {task}')
    # Android removes the task just after `am stack remove` returns (first moved behind, gone ~0.1 s later;
    # about 4 s on a busy emulator)
    s1.until(lambda: task not in [i for i, _, _ in tasks()], f'task {task} swiped away: {tasks()}', seconds=15)


def main():
    s1.require_this_broker()
    on_main = set(apps_on(0))
    if on_main & {SETTINGS, APP}:
        sys.exit(f'clear {sorted(on_main & {SETTINGS, APP})} from the main screen first')

    t1, t2 = str(uuid.uuid4()), str(uuid.uuid4())
    try:
        # 1. APP is in front on the main screen before the thread asks for it
        user_opens(APP)
        got = tool(t1, 'request_apps', {'packages': [APP], 'taskTitle': 'S2 takeover'})
        display = got['displayId']
        check(got['heldApps'] == [APP], f'B3: requesting is not checked: {got}')
        tool(t1, 'tap', {'x': 5, 'y': 5})  # no USER_HOLDS_APP
        tool(t1, 'open_app', {'packageName': APP})
        s1.until(lambda: task_of(APP, display), f'B3: {APP} taken over onto the thread\'s screen: {tasks()}')
        tool(t1, 'release_apps', {'packages': [APP]})
        turn_ended(t1)

        # 2. the user opens Settings while the thread uses it
        display = tool(t2, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S2 settings'})['displayId']
        tool(t2, 'open_app', {'packageName': SETTINGS})
        page = s1.until(lambda: task_of(SETTINGS, display), f'Settings on the thread\'s screen: {tasks()}')
        moved = user_opens(SETTINGS)
        tool(t2, 'tap', {'x': 5, 'y': 5})  # still runs
        tool(t2, 'open_app', {'packageName': SETTINGS})
        again = s1.until(lambda: task_of(SETTINGS, display), f'B3: Settings back on the thread\'s screen: {tasks()}')
        check(again is not None, f'B3: taken back: {page} {moved} -> {tasks()}')

        # 3. C4: once the turn has ended, the user gets a new Settings task, not the thread's page
        turn_ended(t2)
        s1.until(lambda: apps_on(display) == [], f'C2: the screen is cleared: {tasks()}', seconds=5)
        users = user_opens(SETTINGS)
        check(users not in (page, again), f'C4: a new Settings task for the user: {tasks()}')
        user_swipes(users)
    finally:
        turn_ended(t1)
        turn_ended(t2)
        for i, p, d in tasks():
            if d == 0 and p in (SETTINGS, APP):
                shell(f'am stack remove {i}')
    print(f'S2: {s1.checks} checks passed')


if __name__ == '__main__':
    main()
