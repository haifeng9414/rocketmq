git config --local user.name haifeng9414
git conifg --local user.email dong_hf@yeah.net

source ~/.ssh/use_dong_hf_key_for_github

message=${1:-'添加笔记'}
git add .
git commit -am "$message"
git push -u haifeng master
