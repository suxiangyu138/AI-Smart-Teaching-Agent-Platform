@echo off
chcp 65001
echo ======================================
echo     GitHub 日常一键同步更新
echo ======================================
echo 1. 拉取远程最新代码...
git pull
echo.

echo 2. 暂存所有修改文件...
git add .
echo.

echo 3. 本地提交更新
git commit -m "日常批量更新 | auto commit"
echo.

echo 4. 推送到 GitHub 远程仓库
git push
echo.

echo ======================================
echo 提交完成！
pause
