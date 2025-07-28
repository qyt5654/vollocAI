package com.itheima;
import java.util.regex.Pattern;
public class UserRegistration {
    public static void main(String[] args) {
        String username = "exampleUser";
        String password = "password123";
        String phoneNumber = "13800138000";
        String verificationCode = "1234";
        String email = "user@example.com";
        String address = "123 Example Street";
        String occupation = "Developer";
        
        if (isValidRegistration(username, password, phoneNumber, verificationCode, email, address, occupation)) {
            System.out.println("注册成功！");
        } else {
            System.out.println("注册失败，请检查输入参数。");
        }
    }
    public static boolean isValidRegistration(String username, String password, String phoneNumber, String verificationCode, String email, String address, String occupation) {
        return isValidUsername(username) &&
               isValidPassword(password) &&
               isValidPhoneNumber(phoneNumber) &&
               isValidVerificationCode(verificationCode) &&
               isValidEmail(email) &&
               isValidAddress(address) &&
               occupation != null && !occupation.isEmpty();
    }
    public static boolean isValidUsername(String username) {
        // 简单的用户名验证，允许字母和数字
        return username != null && username.matches("^[a-zA-Z0-9]+$");
    }
    public static boolean isValidPassword(String password) {
        // 简单的密码验证，至少6位
        return password != null && password.length() >= 6;
    }
    public static boolean isValidPhoneNumber(String phoneNumber) {
        // 简单的手机号验证，以1开头的11位数字
        return phoneNumber != null && phoneNumber.matches("^1[3-9]\\d{9}$");
    }
    public static boolean isValidVerificationCode(String verificationCode) {
        // 简单的验证码验证，4位数字
        return verificationCode != null && verificationCode.matches("^\\d{4}$");
    }
    public static boolean isValidEmail(String email) {
        // 简单的邮箱验证，使用正则表达式
        String emailRegex = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
        return email != null && Pattern.matches(emailRegex, email);
    }
    public static boolean isValidAddress(String address) {
        // 简单的地址验证，不允许为空
        return address != null && !address.isEmpty();
    }
}