package com.vollocAI.ai.demo;

import com.vollocAI.ai.context.LoginContextHolder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BugDemo {
    
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    
    public static void main(String[] args) throws InterruptedException {
        // 父线程设置登录用户 A
        LoginContextHolder.set("loginId", 100L);
        
        // 提交任务到线程池
        pool.submit(() -> {
            // 子线程"继承"了父线程的 Map（同一个对象！）
            System.out.println("子线程1读取: " + LoginContextHolder.getLoginId()); // 100
            
            // 子线程修改，会影响父线程！
            LoginContextHolder.set("loginId", 999L);
        });
        
        // 等待子线程执行
        Thread.sleep(100);
        
        // 父线程的值被篡改了！
        System.out.println("父线程读取: " + LoginContextHolder.getLoginId()); // 999！不是100
    }
}