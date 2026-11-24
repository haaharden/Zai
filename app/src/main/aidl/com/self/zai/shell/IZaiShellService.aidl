package com.self.zai.shell;

interface IZaiShellService {
    void destroy() = 16777114;
    void exit() = 1;
    int getUid() = 2;
    String exec(String command, int timeoutMs) = 3;
    byte[] screencapPng() = 4;
}
