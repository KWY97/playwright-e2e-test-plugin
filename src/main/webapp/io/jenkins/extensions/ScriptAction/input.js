if (window._psInitDone) {
    // console.log('input.js: already initialized');
} else {
    window._psInitDone = true;

    document.addEventListener('DOMContentLoaded', () => {
        const editor = document.getElementById('editor');
        const template = document
            .getElementById('scenario-template')
            .querySelector('.scenario');
        const addScenarioBtn = document.getElementById('addScenario');
        const saveButton = document.getElementById('saveButton');

        // 1) 이벤트 위임으로 스텝/시나리오 버튼 처리
        editor.addEventListener('click', (e) => {
            if (e.target.matches('.add-step')) {
                // + 스텝 추가
                const scn = e.target.closest('.scenario');
                const stepsDiv = scn.querySelector('.steps');
                const newStep = template
                    .querySelector('.steps > div')
                    .cloneNode(true);
                newStep.querySelector('input.st-text').value = '';
                stepsDiv.appendChild(newStep);
            } else if (e.target.matches('.del-step')) {
                // ✕ 스텝 삭제
                const stepDiv = e.target.closest('.steps > div');
                if (stepDiv) stepDiv.remove();
            } else if (e.target.matches('.del-scenario')) {
                // ✕ 시나리오 삭제
                const scnDiv = e.target.closest('.scenario');
                if (scnDiv) scnDiv.remove();
            }
        });

        // 2) 시나리오 추가 버튼
        addScenarioBtn.addEventListener('click', () => {
            const newScn = template.cloneNode(true);
            newScn.querySelector('input.sc-title').value = '';
            newScn.querySelectorAll('input.st-text').forEach(i => i.value = '');
            editor.appendChild(newScn);
        });

        // 3) 저장 전 모델 직렬화
        function prepareSave() {
            const model = {
                title: document.getElementById('scriptTitle').value,
                scenarios: []
            };
            editor.querySelectorAll('.scenario').forEach(scn => {
                const title = scn.querySelector('input.sc-title').value;
                const steps = Array.from(scn.querySelectorAll('input.st-text'))
                    .map(i => i.value);
                model.scenarios.push({ title, steps });
            });
            document.getElementById('jsonData').value =
                JSON.stringify(model, null, 2);
        }

        // 4) 저장 버튼 핸들러 (CSRF crumb 포함)
        saveButton.addEventListener('click', function(e) {
            e.preventDefault();
            prepareSave();

            const form = this.form || this.closest('form');
            if (!form) {
                console.error('Save button is not inside a form.');
                return;
            }

            // Jenkins 크럼 토큰 메타 태그에서 추출
            const crumbFieldMeta = document.querySelector('meta[name="crumbRequestField"]');
            const crumbMeta      = document.querySelector('meta[name="crumb"]');
            if (crumbFieldMeta && crumbMeta) {
                const fieldName = crumbFieldMeta.content;
                const token     = crumbMeta.content;
                if (!form.querySelector(`input[name="${fieldName}"]`)) {
                    const inp = document.createElement('input');
                    inp.type  = 'hidden';
                    inp.name  = fieldName;
                    inp.value = token;
                    form.appendChild(inp);
                }
            } else {
                console.warn('Jenkins crumb meta tags not found; CSRF may fail.');
            }

            form.submit();
        });
    });
}